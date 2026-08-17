package com.metaml.workbench.generation;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.codegen.DelegateClassGenerator;
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

// Generates the Target Harness Platform from the camundademo template.
// Generation assembles files; SpringBootProjectLauncher handles execution.
// Manufacturing and Twin run as separate modules within one Spring Boot application.
// Package rewriting and Twin generation are project-specific additions.
@Component
public class SpringBootProjectGenerator {

    private static final Logger logger = LoggerFactory.getLogger(SpringBootProjectGenerator.class);

    // Callers pre-render against this placeholder; generate() rewrites it to the real project package.
    public static final String DELEGATE_PACKAGE = "com.example.camundademo.delegate";

    // The template's own package; rewritePackage() replaces all occurrences with the project-specific base package.
    private static final String TEMPLATE_BASE_PACKAGE = "com.example.camundademo";
    private static final String TEMPLATE_BASE_PACKAGE_PATH = "com/example/camundademo";

    // Professor: "it should not be Spring Boot project, it should be target harness platform" -
    // and "javacom target platform redcollar" as his own example of the per-project package he
    // wants. Never Red Collar itself: the slug is derived from whatever process key the saved
    // model actually declares (see packageSlugFor), so this stays generic across every project.
    private static final String TARGET_PLATFORM_BASE_PACKAGE = "com.metaml.targetplatform";

    private static final String DELEGATE_PACKAGE_PATH = "src/main/java/com/example/camundademo/delegate";
    private static final String CONTROLLER_PACKAGE_PATH = "src/main/java/com/example/camundademo/controller";
    private static final String PROCESSES_PATH = "src/main/resources/processes";

    private final Path templateDirectory;
    private final Path outputDirectory;
    private final TwinModelGenerator twinModelGenerator;
    private final DelegateClassGenerator delegateClassGenerator;

    public SpringBootProjectGenerator(
            @Value("${workbench.generation.template-directory:./templates/camundademo}") String templateDirectory,
            @Value("${workbench.generation.output-directory:./data/generated-projects}") String outputDirectory,
            TwinModelGenerator twinModelGenerator, DelegateClassGenerator delegateClassGenerator) {
        this.templateDirectory = Path.of(templateDirectory);
        this.outputDirectory = Path.of(outputDirectory);
        this.twinModelGenerator = twinModelGenerator;
        this.delegateClassGenerator = delegateClassGenerator;
    }

    public GeneratedProject generate(String bpmnXml, List<GeneratedDelegate> delegates) {
        if (!Files.isDirectory(templateDirectory)) {
            throw new IllegalStateException("No template project at " + templateDirectory.toAbsolutePath()
                    + " - workbench.generation.template-directory must point at the camundademo template");
        }
        // Parse once; both the process key and activity list read from the same instance.
        BpmnModelInstance model = Bpmn.readModelFromStream(
                new ByteArrayInputStream(bpmnXml.getBytes(StandardCharsets.UTF_8)));
        String processKey = extractProcessKey(model);
        List<BpmnActivities.Activity> activities = BpmnActivities.eligible(model);
        String projectId = UUID.randomUUID().toString();
        Path projectDir = outputDirectory.resolve(projectId);
        String basePackage = TARGET_PLATFORM_BASE_PACKAGE + "." + packageSlugFor(processKey);

        copyTemplate(projectDir);
        removeTemplatePlaceholders(projectDir);
        rewritePackage(projectDir, basePackage);
        writeProcessFile(projectDir, processKey, bpmnXml);
        writeManufacturingDelegates(projectDir, basePackage, delegates);
        writeController(projectDir, basePackage, "controller.manufacturing", "GeneratedManufacturingController",
                "/api/v1/manufacturing", processKey, activities, "notifyTwin");
        generateTwinResources(projectDir, basePackage, model, processKey);

        logger.info(
                "Generated Target Harness Platform {} for process key '{}' with {} manufacturing activity "
                        + "endpoint(s), package {}, at {}",
                projectId, processKey, activities.size(), basePackage, projectDir.toAbsolutePath());
        return new GeneratedProject(projectId, projectDir, processKey);
    }

    // Reuses TwinModelGenerator's stateless transform (same as WorkbenchServiceImpl.deployTwinDefinition,
    // ADR-002/ADR-005) without touching the Workbench's engine, RepositoryService, or Evolve.
    // Twin delegates render directly against basePackage, so no placeholder rewriting is needed.
    private void generateTwinResources(Path projectDir, String basePackage, BpmnModelInstance originalModel,
            String processKey) {
        BpmnModelInstance twinModel = twinModelGenerator.generate(originalModel);
        String twinBpmnXml = Bpmn.convertToString(twinModel);
        String twinProcessKey = processKey + "_twin";
        writeFile(projectDir.resolve(PROCESSES_PATH).resolve(twinProcessKey + ".bpmn"), twinBpmnXml);

        List<GeneratedDelegate> twinDelegates =
                delegateClassGenerator.generate(twinBpmnXml, basePackage + ".delegate.twin");
        writeTwinDelegates(projectDir, basePackage, twinDelegates);

        List<BpmnActivities.Activity> twinActivities = BpmnActivities.eligible(twinModel);
        writeController(projectDir, basePackage, "controller.twin", "GeneratedTwinController", "/api/v1/twin",
                twinProcessKey, twinActivities, "notifyManufacturing");
    }

    // Lowercase processKey with non-alpha stripped; digit-leading or empty keys fall back to "generated<slug>".
    private static String packageSlugFor(String processKey) {
        String slug = processKey == null ? "" : processKey.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (slug.isEmpty() || Character.isDigit(slug.charAt(0))) {
            slug = "generated" + slug;
        }
        return slug;
    }

    // Rewrites the template's com/example/camundademo tree to the project-specific package; non-Java resources unchanged.
    private void rewritePackage(Path projectDir, String basePackage) {
        // Both source roots: copied test sources reference template classes; rewriting main only breaks test-compile.
        rewritePackageUnder(projectDir.resolve("src/main/java"), basePackage);
        rewritePackageUnder(projectDir.resolve("src/test/java"), basePackage);
    }

    private void rewritePackageUnder(Path sourceRoot, String basePackage) {
        Path oldRoot = sourceRoot.resolve(TEMPLATE_BASE_PACKAGE_PATH);
        if (!Files.isDirectory(oldRoot)) {
            return;
        }
        Path newRoot = sourceRoot.resolve(basePackage.replace('.', '/'));
        try (Stream<Path> walk = Files.walk(oldRoot)) {
            List<Path> files = walk.filter(Files::isRegularFile).toList();
            for (Path source : files) {
                Path relative = oldRoot.relativize(source);
                Path target = newRoot.resolve(relative);
                Files.createDirectories(target.getParent());
                String content = Files.readString(source, StandardCharsets.UTF_8);
                String rewritten = content.replace(TEMPLATE_BASE_PACKAGE, basePackage);
                Files.writeString(target, rewritten, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not rewrite template package under " + oldRoot.toAbsolutePath(),
                    e);
        }
        deleteTree(oldRoot);
    }

    private static void deleteTree(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            logger.warn("Could not fully remove pre-rewrite package tree {}: {}", root.toAbsolutePath(),
                    e.toString());
        }
    }

    // NotificationBridge ships with the template alongside the messaging package - where the
    // professor said RabbitMQ belongs: "we'll add the rabbit in Q to the template repository. And
    // then that becomes your updated generated set."
    // copyTemplate() + rewritePackage() handle the rest; nothing is generated here.

    // Reconstructs the in-memory registry from disk rather than a separate store that could drift.
    // Directories without exactly one non-twin .bpmn file are skipped, not guessed at.
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

    // Takes projectId, not Path - validates direct-child relationship before deletion to prevent
    // path traversal (id comes from a persisted event; "../../data" would otherwise escape the tree).
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
            // A generated project holds no state anything depends on, so a partial delete doesn't
            // fail the triggering operation - scanExisting() above already skips directories with
            // no resolvable process key, which is exactly the shape a half-deleted one has.
            logger.warn("Could not fully delete generated project {} at {}: {}", projectId, projectDir, e.toString());
            return false;
        }
        logger.info("Deleted superseded generated project {} at {}", projectId, projectDir);
        return true;
    }

    // Filter the twin file first; generate() writes two BPMN files but only one (non-twin) identifies the process key.
    private static String findProcessKey(Path projectDir) {
        Path processesDir = projectDir.resolve(PROCESSES_PATH);
        if (!Files.isDirectory(processesDir)) {
            return null;
        }
        try (Stream<Path> entries = Files.list(processesDir)) {
            List<Path> bpmnFiles = entries.filter(p -> p.getFileName().toString().endsWith(".bpmn"))
                    .filter(p -> !p.getFileName().toString().endsWith("_twin.bpmn"))
                    .toList();
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

    // Remove the template's loanApproval worked example; writeController() replaces the controller.
    // LoanApplicationContext and BPMNProcessRESTMappings exist only to support the removed pair.
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

    // Pre-rendered delegates still reference DELEGATE_PACKAGE; rewrite to the manufacturing subpackage at write time.
    private void writeManufacturingDelegates(Path projectDir, String basePackage, List<GeneratedDelegate> delegates) {
        String manufacturingPackage = basePackage + ".delegate.manufacturing";
        Path packageDir = projectDir.resolve("src/main/java").resolve(manufacturingPackage.replace('.', '/'));
        for (GeneratedDelegate delegate : delegates) {
            String rewrittenSource = delegate.sourceCode().replace(DELEGATE_PACKAGE, manufacturingPackage);
            writeDelegateFile(packageDir, delegate.className(), rewrittenSource, delegate.beanName(),
                    delegate.bpmnElementId());
        }
    }

    private void writeTwinDelegates(Path projectDir, String basePackage, List<GeneratedDelegate> delegates) {
        Path packageDir = projectDir.resolve("src/main/java")
                .resolve((basePackage + ".delegate.twin").replace('.', '/'));
        for (GeneratedDelegate delegate : delegates) {
            writeDelegateFile(packageDir, delegate.className(), delegate.sourceCode(), delegate.beanName(),
                    delegate.bpmnElementId());
        }
    }

    private void writeDelegateFile(Path packageDir, String className, String sourceCode, String beanName,
            String bpmnElementId) {
        Path target = packageDir.resolve(className + ".java");
        // not the shared writeFile() helper below - a failure here is attributable to this one
        // delegate specifically, which writeFile's own generic UncheckedIOException has no way
        // to say (see DelegateWriteException's own comment)
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, sourceCode, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DelegateWriteException("Could not write " + target.toAbsolutePath(), e, beanName,
                    bpmnElementId);
        }
    }

    // Generates one endpoint per BPMN activity (N activities → N endpoints), replacing the
    // template's loanApproval-hardcoded controller. A single method produces both controllers, as
    // the professor asked: "controller-twin controller-manuf... right? Um and then the delegates
    // you can have the the twin side delegates twin delegates manufacturing"
    // bridgeMethod distinguishes which NotificationBridge side is called after each activity.
    private void writeController(Path projectDir, String basePackage, String subPackage, String className,
            String requestMapping, String processKey, List<BpmnActivities.Activity> activities,
            String bridgeMethod) {
        Set<BpmnActivities.Trigger> triggers = activities.stream()
                .map(BpmnActivities.Activity::trigger)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        StringBuilder endpoints = new StringBuilder();
        for (BpmnActivities.Activity activity : activities) {
            endpoints.append(renderActivityEndpoint(activity, bridgeMethod));
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

        // Inject ExternalTaskService only when needed; an unused unresolvable bean fails startup.
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
                package %s.%s;

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

                import %s.bridge.NotificationBridge;

                // Generated for process key "%s" - not hand-written, don't hand-edit; regenerate instead.
                // One endpoint per externally-triggerable BPMN activity, generated from the model itself.
                // Calls NotificationBridge.%s after completing each activity (see NotificationBridge).
                @RestController
                @RequestMapping("%s")
                public class %s {

                    private final RuntimeService runtimeService;
                    private final TaskService taskService;
                    private final NotificationBridge notificationBridge;
                %s
                    public %s(RuntimeService runtimeService, TaskService taskService,
                            NotificationBridge notificationBridge%s) {
                        this.runtimeService = runtimeService;
                        this.taskService = taskService;
                        this.notificationBridge = notificationBridge;
                %s    }

                    @PostMapping("/start")
                    public ResponseEntity<Map<String, String>> start() {
                        ProcessInstance instance = runtimeService.startProcessInstanceByKey("%s");
                        return ResponseEntity.ok(Map.of("processInstanceId", instance.getId()));
                    }
                %s%s}
                """.formatted(basePackage, subPackage, externalImport, executionImport, taskImport, basePackage,
                processKey, bridgeMethod, requestMapping, className, externalField, className, externalParam,
                externalAssignment, processKey, endpoints, helpers);
        Path packageDir = projectDir.resolve("src/main/java")
                .resolve((basePackage + "." + subPackage).replace('.', '/'));
        writeFile(packageDir.resolve(className + ".java"), source);
    }

    // One endpoint per activity; the activity ID is a literal so a slug mismatch cannot reach the wrong task.
    private static String renderActivityEndpoint(BpmnActivities.Activity activity, String bridgeMethod) {
        String label = activity.name() == null || activity.name().isBlank()
                ? "(unnamed activity)"
                : sanitizeForComment(activity.name());
        return """

                    // BPMN activity "%s" (id: %s), triggered as %s
                    @PostMapping("/{processInstanceId}/%s/complete")
                    public ResponseEntity<Map<String, List<String>>> complete%s(@PathVariable String processInstanceId) {
                        ResponseEntity<Map<String, List<String>>> response = %s(processInstanceId, "%s");
                        notificationBridge.%s(processInstanceId, "%s");
                        return response;
                    }
                """.formatted(label, activity.id(), activity.trigger(), activity.endpointSlug(),
                activity.methodSuffix(), helperMethodFor(activity.trigger()), activity.id(), bridgeMethod,
                activity.id());
    }

    private static String helperMethodFor(BpmnActivities.Trigger trigger) {
        return switch (trigger) {
            case USER_TASK -> "completeUserTask";
            case RECEIVE_TASK -> "signalReceiveTask";
            case EXTERNAL_TASK -> "completeExternalTask";
        };
    }

    // BPMN names can contain embedded newlines (loanApproval.bpmn: "Calculate\nInterest"); collapse to spaces for inline comments.
    private static String sanitizeForComment(String label) {
        return label.replaceAll("\\s+", " ").trim();
    }

    // Query by BPMN element ID; multi-instance activities may have multiple open tasks.
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

    // Receive tasks have no Task row; signal the parked execution by activity ID.
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

    // External tasks require the worker to hold the lock before completion.
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

    // No matching task means the process is not currently at this activity.
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
