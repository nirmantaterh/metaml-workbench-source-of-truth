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
import java.util.List;
import java.util.UUID;
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
        String processKey = extractProcessKey(bpmnXml);
        String projectId = UUID.randomUUID().toString();
        Path projectDir = outputDirectory.resolve(projectId);

        copyTemplate(projectDir);
        removeTemplatePlaceholders(projectDir);
        writeProcessFile(projectDir, processKey, bpmnXml);
        writeDelegates(projectDir, delegates);
        writeController(projectDir, processKey);

        logger.info("Generated Spring Boot project {} for process key '{}' at {}",
                projectId, processKey, projectDir.toAbsolutePath());
        return new GeneratedProject(projectId, projectDir, processKey);
    }

    private static String extractProcessKey(String bpmnXml) {
        BpmnModelInstance model = Bpmn.readModelFromStream(
                new ByteArrayInputStream(bpmnXml.getBytes(StandardCharsets.UTF_8)));
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
            writeFile(projectDir.resolve(DELEGATE_PACKAGE_PATH).resolve(delegate.className() + ".java"),
                    delegate.sourceCode());
        }
    }

    private void writeController(Path projectDir, String processKey) {
        // replaces the template's own Camundacontroller.java (hardcoded to
        // startProcessInstanceByKey("loanApproval")) with one built around whatever process this
        // project was actually generated for. Two endpoints per the priority-ordered task list's
        // own demonstration requirement: a process has to be startable AND completable to actually
        // demonstrate an activity being evolved end to end, not just started.
        String source = """
                package com.example.camundademo.controller;

                import java.util.ArrayList;
                import java.util.List;
                import java.util.Map;

                import org.camunda.bpm.engine.RuntimeService;
                import org.camunda.bpm.engine.TaskService;
                import org.camunda.bpm.engine.runtime.ProcessInstance;
                import org.camunda.bpm.engine.task.Task;
                import org.springframework.http.ResponseEntity;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                // Generated for process key "%s" - not hand-written, don't hand-edit; regenerate instead.
                @RestController
                @RequestMapping("/api/v1/process")
                public class GeneratedProcessController {

                    private final RuntimeService runtimeService;
                    private final TaskService taskService;

                    public GeneratedProcessController(RuntimeService runtimeService, TaskService taskService) {
                        this.runtimeService = runtimeService;
                        this.taskService = taskService;
                    }

                    @PostMapping("/start")
                    public ResponseEntity<Map<String, String>> start() {
                        ProcessInstance instance = runtimeService.startProcessInstanceByKey("%s");
                        return ResponseEntity.ok(Map.of("processInstanceId", instance.getId()));
                    }

                    // completes every currently open task on the instance, same "whatever's open right now"
                    // semantics the Workbench's own completeCurrentTasks uses - a parallel gateway can leave
                    // more than one task open at once
                    @PostMapping("/{processInstanceId}/complete-task")
                    public ResponseEntity<Map<String, List<String>>> completeTask(@PathVariable String processInstanceId) {
                        List<Task> openTasks = taskService.createTaskQuery()
                                .processInstanceId(processInstanceId)
                                .list();
                        List<String> completed = new ArrayList<>();
                        for (Task task : openTasks) {
                            taskService.complete(task.getId());
                            completed.add(task.getName() != null ? task.getName() : task.getId());
                        }
                        return ResponseEntity.ok(Map.of("completed", completed));
                    }
                }
                """.formatted(processKey, processKey);
        writeFile(projectDir.resolve(CONTROLLER_PACKAGE_PATH).resolve("GeneratedProcessController.java"), source);
    }

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
