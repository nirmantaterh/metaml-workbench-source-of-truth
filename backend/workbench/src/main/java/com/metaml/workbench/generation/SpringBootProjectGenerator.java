package com.metaml.workbench.generation;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.metaml.workbench.codegen.GeneratedDelegate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// New scope item 4 (Spring Boot Generation), the file-assembly half of it. Copies Joanna's
// camundademo template, drops the saved model's BPMN into its processes folder (the template's
// own CamundaConfig already auto-deploys anything matching classpath*:/processes/*.bpmn - no code
// change needed for that part), writes every generated delegate class in next to the template's
// own, and replaces the template's hardcoded loanApproval controller with one built around
// whatever process key the saved model actually declares.
//
// Deliberately does NOT launch anything - that's SpringBootProjectLauncher, kept separate so this
// class stays trivially testable (assemble files, assert they're right) without needing to spin
// up a real child JVM in a unit test.
@Component
public class SpringBootProjectGenerator {

    private static final Logger logger = LoggerFactory.getLogger(SpringBootProjectGenerator.class);

    // the delegates this generator writes into a project MUST already be rendered with this exact
    // package name (see DelegateClassGenerator.generate(bpmnXml, packageName)) - it has to match
    // DELEGATE_PACKAGE_PATH below or the class compiles fine but Spring's default component scan
    // (rooted at com.example.camundademo, the template's @SpringBootApplication package) never
    // finds it, and the generated app fails at runtime instead of at build time
    public static final String DELEGATE_PACKAGE = "com.example.camundademo.delegates";

    private static final String DELEGATE_PACKAGE_PATH = "src/main/java/com/example/camundademo/delegates";
    private static final String CONTROLLER_PACKAGE_PATH = "src/main/java/com/example/camundademo/controller";
    private static final String PROCESSES_PATH = "src/main/resources/processes";

    private final Path templateDirectory;
    private final Path outputDirectory;

    public SpringBootProjectGenerator(
            @Value("${workbench.generation.template-directory:./templates/camundademo}") String templateDirectory,
            @Value("${workbench.generation.output-directory:./data/generated-projects}") String outputDirectory) {
        this.templateDirectory = Path.of(templateDirectory);
        this.outputDirectory = Path.of(outputDirectory);
    }

    public GeneratedProject generate(String bpmnXml, List<GeneratedDelegate> delegates) {
        if (!Files.isDirectory(templateDirectory)) {
            throw new IllegalStateException("No template project at " + templateDirectory.toAbsolutePath()
                    + " - workbench.generation.template-directory must point at the camundademo template");
        }
        // parsed once and reused - the controller now needs the activity list as well as the
        // process key, and re-reading the same XML a second time just to ask a second question
        // about it would leave two parses that could drift apart
        BpmnModelInstance model = Bpmn.readModelFromStream(
                new ByteArrayInputStream(bpmnXml.getBytes(StandardCharsets.UTF_8)));
        String processKey = extractProcessKey(model);
        List<BpmnActivities.Activity> activities = BpmnActivities.eligible(model);
        String projectId = UUID.randomUUID().toString();
        Path projectDir = outputDirectory.resolve(projectId);

        copyTemplate(projectDir);
        removeTemplatePlaceholders(projectDir);
        writeProcessFile(projectDir, processKey, bpmnXml);
        writeDelegates(projectDir, delegates);
        writeController(projectDir, processKey, activities);

        logger.info("Generated Spring Boot project {} for process key '{}' with {} activity endpoint(s) at {}",
                projectId, processKey, activities.size(), projectDir.toAbsolutePath());
        return new GeneratedProject(projectId, projectDir, processKey);
    }

    // Restart persistence: rebuilds the projectId -> GeneratedProject registry from the
    // generated-projects directory itself instead of from anything WorkbenchServiceImpl would
    // otherwise have to persist separately. Both fields a GeneratedProject actually carries
    // beyond its id are already implied by this class's own fixed layout - the directory is
    // exactly outputDirectory/{projectId} (generate() above never places it anywhere else), and
    // the process key is the basename of whatever single .bpmn file writeProcessFile() put under
    // PROCESSES_PATH. Nothing here is new information; it is only ever re-deriving what generate()
    // already committed to disk, which is why this is a scan rather than a second store that could
    // drift out of sync with the directory it is describing.
    //
    // A project directory that does not resolve to exactly one .bpmn file is skipped, not guessed
    // at - a caller later asking for that projectId gets the same clear "not found" it would have
    // gotten if the project never existed, rather than this method fabricating a process key for a
    // directory that cannot actually be launched.
    public List<GeneratedProject> scanExisting() {
        if (!Files.isDirectory(outputDirectory)) {
            // nothing has ever been generated against this output directory - a fresh install, or
            // one still on defaults, not an error
            return List.of();
        }
        List<GeneratedProject> found = new ArrayList<>();
        try (Stream<Path> children = Files.list(outputDirectory)) {
            for (Path projectDir : (Iterable<Path>) children.filter(Files::isDirectory)::iterator) {
                String projectId = projectDir.getFileName().toString();
                String processKey = findProcessKey(projectDir);
                if (processKey == null) {
                    logger.warn("Skipping {} while restoring generated projects - could not determine a single "
                            + "process key under {}", projectId, projectDir.resolve(PROCESSES_PATH));
                    continue;
                }
                found.add(new GeneratedProject(projectId, projectDir, processKey));
            }
        } catch (IOException e) {
            logger.warn("Could not scan {} for existing generated projects: {}", outputDirectory.toAbsolutePath(),
                    e.toString());
            return List.of();
        }
        return found;
    }

    // Retention (latest generation only): removes one generated project's directory. Deliberately
    // takes a projectId rather than a Path - this class is the only thing that decides where a
    // generated project lives (generate() resolves outputDirectory/{projectId} and nothing else
    // ever does), so it's also the only thing that can safely turn an id back into a directory to
    // remove. A caller passing a Path would be asserting a layout it doesn't own.
    //
    // WHICH project is disposable is not decided here - that's a workflow-history question
    // (WorkbenchServiceImpl.cleanupSupersededProjects), and this class has no idea which model a
    // project belongs to or which generation is current. This only does the removal it's told to,
    // and only after proving the id resolves to a direct child of the output directory: projectId
    // reaches this from a persisted workflow event, so an id like "../../data" would otherwise
    // recursively delete something well outside the generated-projects tree.
    public boolean delete(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return false;
        }
        Path root = outputDirectory.toAbsolutePath().normalize();
        Path projectDir = root.resolve(projectId).normalize();
        if (!root.equals(projectDir.getParent())) {
            logger.warn("Refusing to delete generated project '{}' - {} is not a direct child of {}",
                    projectId, projectDir, root);
            return false;
        }
        if (!Files.isDirectory(projectDir)) {
            // already gone (deleted by hand, or a previous cleanup got there first) - not an error,
            // the caller's intended end state is exactly what's already true
            return false;
        }
        try (Stream<Path> walk = Files.walk(projectDir)) {
            // deepest-first, so a directory is only removed once it's already empty
            for (Path path : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            // A generated project holds no state anything else depends on, so a partial delete is
            // not worth failing the operation that triggered it (a regenerate, a stop, a restart).
            // scanExisting() above already skips a directory it can't resolve a single process key
            // from, which is exactly the shape a half-deleted directory has.
            logger.warn("Could not fully delete generated project {} at {}: {}", projectId, projectDir, e.toString());
            return false;
        }
        logger.info("Deleted superseded generated project {} at {}", projectId, projectDir);
        return true;
    }

    private static String findProcessKey(Path projectDir) {
        Path processesDir = projectDir.resolve(PROCESSES_PATH);
        if (!Files.isDirectory(processesDir)) {
            return null;
        }
        try (Stream<Path> entries = Files.list(processesDir)) {
            List<Path> bpmnFiles = entries.filter(p -> p.getFileName().toString().endsWith(".bpmn")).toList();
            // zero means this directory was never finished (or was cleared out); more than one is
            // a shape generate() itself never produces - either way, no safe single answer
            if (bpmnFiles.size() != 1) {
                return null;
            }
            String fileName = bpmnFiles.get(0).getFileName().toString();
            return fileName.substring(0, fileName.length() - ".bpmn".length());
        } catch (IOException e) {
            return null;
        }
    }

    private static String extractProcessKey(BpmnModelInstance model) {
        return model.getModelElementsByType(Process.class).stream()
                .findFirst()
                .map(Process::getId)
                .orElseThrow(() -> new IllegalArgumentException("BPMN has no process element to read a key from"));
    }

    private void copyTemplate(Path projectDir) {
        try (Stream<Path> walk = Files.walk(templateDirectory)) {
            for (Path source : (Iterable<Path>) walk::iterator) {
                Path relative = templateDirectory.relativize(source);
                Path target = projectDir.resolve(relative.toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Could not copy template from " + templateDirectory.toAbsolutePath(), e);
        }
    }

    // the template ships with its own worked example (loanApproval.bpmn,
    // CalculateInterestService, and a Camundacontroller hardcoded to
    // startProcessInstanceByKey("loanApproval")) so it's runnable standing alone. A generated
    // project shouldn't carry any of that: the BPMN would deploy alongside whatever the user
    // actually modeled under the classpath*:/processes/*.bpmn pattern, and the old controller
    // would throw the moment it's called against a project whose process key isn't literally
    // "loanApproval" - writeController() replaces it, this just clears the way first.
    // LoanApplicationContext and BPMNProcessRESTMappings go too: both exist only to support the
    // controller/delegate pair being removed, and the former was already inert in the template
    // itself (no @Configuration annotation, so Spring never picked up its @Bean method anyway).
    private void removeTemplatePlaceholders(Path projectDir) {
        deleteIfExists(projectDir.resolve(PROCESSES_PATH).resolve("loanApproval.bpmn"));
        deleteIfExists(projectDir.resolve(DELEGATE_PACKAGE_PATH).resolve("CalculateInterestService.java"));
        deleteIfExists(projectDir.resolve(CONTROLLER_PACKAGE_PATH).resolve("Camundacontroller.java"));
        deleteIfExists(projectDir.resolve("src/main/java/com/example/camundademo/context/LoanApplicationContext.java"));
        deleteIfExists(projectDir.resolve(
                "src/main/java/com/example/camundademo/utils/restmappings/BPMNProcessRESTMappings.java"));
    }

    private void writeProcessFile(Path projectDir, String processKey, String bpmnXml) {
        writeFile(projectDir.resolve(PROCESSES_PATH).resolve(processKey + ".bpmn"), bpmnXml);
    }

    private void writeDelegates(Path projectDir, List<GeneratedDelegate> delegates) {
        for (GeneratedDelegate delegate : delegates) {
            Path target = projectDir.resolve(DELEGATE_PACKAGE_PATH).resolve(delegate.className() + ".java");
            // not the shared writeFile() helper below - a failure here is attributable to this one
            // delegate specifically, which writeFile's own generic UncheckedIOException has no way
            // to say (see DelegateWriteException's own comment)
            try {
                Files.createDirectories(target.getParent());
                Files.writeString(target, delegate.sourceCode(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new DelegateWriteException("Could not write " + target.toAbsolutePath(), e,
                        delegate.beanName(), delegate.bpmnElementId());
            }
        }
    }

    // Replaces the template's own Camundacontroller.java (hardcoded to
    // startProcessInstanceByKey("loanApproval")) with one built around whatever process this
    // project was actually generated for.
    //
    // Scope item 4, revised: this used to emit a single generic /{id}/complete-task that every
    // activity in the model went through. Joanna confirmed the generated app has to expose one
    // endpoint per BPMN activity instead, so the completion half is now generated per activity -
    // N activities produce N endpoints. The generic one is gone rather than kept alongside:
    // leaving it would mean the old "any activity through one door" path still existed, which is
    // the exact thing being replaced. Nothing outside the generated project ever called it (the
    // workbench's own /wb/transmute/complete-task is a different endpoint on a different app).
    private void writeController(Path projectDir, String processKey, List<BpmnActivities.Activity> activities) {
        Set<BpmnActivities.Trigger> triggers = activities.stream()
                .map(BpmnActivities.Activity::trigger)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        StringBuilder endpoints = new StringBuilder();
        for (BpmnActivities.Activity activity : activities) {
            endpoints.append(renderActivityEndpoint(activity));
        }

        // only the helpers something actually calls, so a generated file never carries a private
        // method nothing references
        StringBuilder helpers = new StringBuilder();
        if (triggers.contains(BpmnActivities.Trigger.USER_TASK)) {
            helpers.append(USER_TASK_HELPER);
        }
        if (triggers.contains(BpmnActivities.Trigger.RECEIVE_TASK)) {
            helpers.append(RECEIVE_TASK_HELPER);
        }
        if (triggers.contains(BpmnActivities.Trigger.EXTERNAL_TASK)) {
            helpers.append(EXTERNAL_TASK_HELPER);
        }
        if (!activities.isEmpty()) {
            helpers.append(RESPOND_HELPER);
        }

        // ExternalTaskService is the one dependency injected conditionally. RuntimeService and
        // TaskService have been in every generated project since before this change and are known
        // good; adding a third to every project would put a bean nothing asked for into the
        // constructor of apps that have no external task at all, and one that fails to resolve
        // takes down startup rather than the endpoint that needed it.
        boolean needsExternalTaskService = triggers.contains(BpmnActivities.Trigger.EXTERNAL_TASK);
        String externalImport = needsExternalTaskService
                ? """
                        import org.camunda.bpm.engine.ExternalTaskService;
                        import org.camunda.bpm.engine.externaltask.ExternalTask;
                        """
                : "";
        String executionImport = triggers.contains(BpmnActivities.Trigger.RECEIVE_TASK)
                ? "import org.camunda.bpm.engine.runtime.Execution;\n"
                : "";
        String taskImport = triggers.contains(BpmnActivities.Trigger.USER_TASK)
                ? "import org.camunda.bpm.engine.task.Task;\n"
                : "";
        String externalField = needsExternalTaskService
                ? "    private final ExternalTaskService externalTaskService;\n"
                : "";
        String externalParam = needsExternalTaskService ? ", ExternalTaskService externalTaskService" : "";
        String externalAssignment = needsExternalTaskService
                ? "        this.externalTaskService = externalTaskService;\n"
                : "";

        String source = """
                package com.example.camundademo.controller;

                import java.util.ArrayList;
                import java.util.List;
                import java.util.Map;

                import org.camunda.bpm.engine.RuntimeService;
                import org.camunda.bpm.engine.TaskService;
                %simport org.camunda.bpm.engine.runtime.ProcessInstance;
                %s%simport org.springframework.http.HttpStatus;
                import org.springframework.http.ResponseEntity;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                // Generated for process key "%s" - not hand-written, don't hand-edit; regenerate instead.
                // One endpoint per externally-triggerable BPMN activity, generated from the model itself.
                @RestController
                @RequestMapping("/api/v1/process")
                public class GeneratedProcessController {

                    private final RuntimeService runtimeService;
                    private final TaskService taskService;
                %s
                    public GeneratedProcessController(RuntimeService runtimeService, TaskService taskService%s) {
                        this.runtimeService = runtimeService;
                        this.taskService = taskService;
                %s    }

                    @PostMapping("/start")
                    public ResponseEntity<Map<String, String>> start() {
                        ProcessInstance instance = runtimeService.startProcessInstanceByKey("%s");
                        return ResponseEntity.ok(Map.of("processInstanceId", instance.getId()));
                    }
                %s%s}
                """.formatted(externalImport, executionImport, taskImport, processKey, externalField,
                externalParam, externalAssignment, processKey, endpoints, helpers);
        writeFile(projectDir.resolve(CONTROLLER_PACKAGE_PATH).resolve("GeneratedProcessController.java"), source);
    }

    // Every endpoint looks the same from outside - POST /<activity>/complete - and differs only in
    // which helper it calls, which is what keeps this loop generic while the execution underneath
    // stays faithful to what the activity actually is.
    //
    // The activity id is passed to the helper as a literal, and the slug only ever appears in the
    // URL - so an endpoint physically cannot reach an activity other than the one it is named for,
    // however the slug was derived.
    private static String renderActivityEndpoint(BpmnActivities.Activity activity) {
        String label = activity.name() == null || activity.name().isBlank()
                ? "(unnamed activity)"
                : sanitizeForComment(activity.name());
        return """

                    // BPMN activity "%s" (id: %s), triggered as %s
                    @PostMapping("/{processInstanceId}/%s/complete")
                    public ResponseEntity<Map<String, List<String>>> complete%s(@PathVariable String processInstanceId) {
                        return %s(processInstanceId, "%s");
                    }
                """.formatted(label, activity.id(), activity.trigger(), activity.endpointSlug(),
                activity.methodSuffix(), helperMethodFor(activity.trigger()), activity.id());
    }

    private static String helperMethodFor(BpmnActivities.Trigger trigger) {
        return switch (trigger) {
            case USER_TASK -> "completeUserTask";
            case RECEIVE_TASK -> "signalReceiveTask";
            case EXTERNAL_TASK -> "completeExternalTask";
        };
    }

    // Same lesson DelegateClassGenerator already learned the hard way: a BPMN name attribute can
    // carry an embedded newline (this repo's own loanApproval.bpmn has "Calculate\nInterest"), and
    // every use of the label here lands inside a single-line // comment, where an unescaped
    // newline turns the rest of the label into a syntax error in the generated file.
    private static String sanitizeForComment(String label) {
        return label.replaceAll("\\s+", " ").trim();
    }

    // Queries by taskDefinitionKey, which is the BPMN element id - the same identity the
    // workbench's own governance and twin bookkeeping already key on (see AgentExecutionDelegate,
    // which reads exactly this value as its activityId). A multi-instance activity can have more
    // than one open task at once, so this completes that activity's set rather than a single task.
    private static final String USER_TASK_HELPER = """

                private ResponseEntity<Map<String, List<String>>> completeUserTask(String processInstanceId,
                        String activityId) {
                    List<Task> openTasks = taskService.createTaskQuery()
                            .processInstanceId(processInstanceId)
                            .taskDefinitionKey(activityId)
                            .list();
                    List<String> completed = new ArrayList<>();
                    for (Task task : openTasks) {
                        taskService.complete(task.getId());
                        completed.add(task.getId());
                    }
                    return respond(completed);
                }
            """;

    // A receive task is a wait state with no Task row behind it, so TaskService cannot see it at
    // all - the execution itself is what is parked, and signalling that execution is what moves
    // the token on. Queried by activityId, the same BPMN element id every other trigger uses.
    private static final String RECEIVE_TASK_HELPER = """

                private ResponseEntity<Map<String, List<String>>> signalReceiveTask(String processInstanceId,
                        String activityId) {
                    List<Execution> waiting = runtimeService.createExecutionQuery()
                            .processInstanceId(processInstanceId)
                            .activityId(activityId)
                            .list();
                    List<String> triggered = new ArrayList<>();
                    for (Execution execution : waiting) {
                        runtimeService.signal(execution.getId());
                        triggered.add(execution.getId());
                    }
                    return respond(triggered);
                }
            """;

    // An external task is the one case where the engine has explicitly handed execution outside
    // itself, so this endpoint IS the external worker. Camunda will not accept a completion from a
    // worker that does not hold the lock, hence lock-then-complete rather than a bare complete.
    private static final String EXTERNAL_TASK_HELPER = """

                // this controller is the worker, so the id only has to be stable and identifiable
                private static final String WORKER_ID = "generated-process-controller";
                // long enough to survive the two calls below, short enough that a crash between
                // them frees the task again rather than stranding it
                private static final long LOCK_MILLIS = 10_000L;

                private ResponseEntity<Map<String, List<String>>> completeExternalTask(String processInstanceId,
                        String activityId) {
                    List<ExternalTask> pending = externalTaskService.createExternalTaskQuery()
                            .processInstanceId(processInstanceId)
                            .activityId(activityId)
                            .list();
                    List<String> completed = new ArrayList<>();
                    for (ExternalTask task : pending) {
                        externalTaskService.lock(task.getId(), WORKER_ID, LOCK_MILLIS);
                        externalTaskService.complete(task.getId(), WORKER_ID);
                        completed.add(task.getId());
                    }
                    return respond(completed);
                }
            """;

    // An empty set means the token is not sitting at this activity, which is a real distinction
    // worth reporting - the old generic endpoint could not make it, because it never asked about a
    // specific activity in the first place.
    private static final String RESPOND_HELPER = """

                private ResponseEntity<Map<String, List<String>>> respond(List<String> touched) {
                    if (touched.isEmpty()) {
                        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("completed", touched));
                    }
                    return ResponseEntity.ok(Map.of("completed", touched));
                }
            """;

    private static void writeFile(Path target, String content) {
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + target.toAbsolutePath(), e);
        }
    }

    private static void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            logger.warn("Could not remove template placeholder {}: {}", path.toAbsolutePath(), e.toString());
        }
    }
}
