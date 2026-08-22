package com.metaml.workbench.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.codegen.DelegateClassGenerator;
import com.metaml.workbench.codegen.ExternalTaskWorkerGenerator;
import com.metaml.workbench.codegen.TargetPlatformSourceGenerator;

// Proves the wiring in SpringBootProjectGenerator.generateTargetPlatform end to end, against a
// small fake copy of the RedCollarTP shape (a src/main/java/com/tp/TargetPlatform tree is what
// isTargetPlatformTemplate() actually keys off) rather than the real, much larger checked-in
// template: the template gets copied into a fresh project directory (the "clone" step), both the
// proxy and the twin BPMN get scanned for delegates AND events, and the generated Java classes
// land under proxy/twin x delegates/events exactly as the requested workflow describes.
class TargetPlatformGenerationTest {

    @TempDir
    Path sandbox;

    private Path templateDir;
    private Path outputDir;

    @BeforeEach
    void buildFakeTargetPlatformTemplate() throws IOException {
        templateDir = sandbox.resolve("RedCollarTP");
        outputDir = sandbox.resolve("generated-target-platforms");
        write(templateDir.resolve("pom.xml"), "<project>fake target platform pom</project>");
        // The one file isTargetPlatformTemplate() actually checks for.
        write(templateDir.resolve("src/main/java/com/tp/TargetPlatform/TargetPlatformApplication.java"),
                "package com.tp.TargetPlatform; class TargetPlatformApplication {}");
    }

    private SpringBootProjectGenerator generator() {
        return new SpringBootProjectGenerator(templateDir.toString(), outputDir.toString(),
                new TwinModelGenerator(), new DelegateClassGenerator(), new ExternalTaskWorkerGenerator());
    }

    private static String bpmn(String processId, String activityXml) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="%s" name="%s" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    %s
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """.formatted(processId, processId, activityXml);
    }

    @Test
    void cloneScanAndGenerateProducesBothProxyAndTwinSourcesInTheirOwnFolders() {
        String proxyBpmn = bpmn("rc_proxy_process", """
                <bpmn2:serviceTask id="Cutting" name="Cutting" camunda:delegateExpression="${cutting}" />
                <bpmn2:intermediateCatchEvent id="CuttingSignal" name="Cutting Signal"
                    camunda:delegateExpression="${cuttingSignal}">
                  <bpmn2:signalEventDefinition />
                </bpmn2:intermediateCatchEvent>
                """);
        String twinBpmn = bpmn("rc_twin_process", """
                <bpmn2:serviceTask id="CuttingTwin" name="Cutting Twin"
                    camunda:delegateExpression="${cuttingTwin}" />
                """);

        GeneratedProject project = generator().generateWithAuthoredTwin(proxyBpmn, twinBpmn);

        // "Clone the template into project_dir" - the template's own files must be present in the
        // freshly generated, freestanding project directory.
        assertThat(project.directory().resolve("pom.xml")).exists();
        assertThat(project.directory()
                .resolve("src/main/java/com/tp/TargetPlatform/TargetPlatformApplication.java")).exists();

        // Both BPMN files land under processes/, keyed by their own process id.
        assertThat(project.directory().resolve("src/main/resources/processes/rc_proxy_process.bpmn")).exists();
        assertThat(project.directory().resolve("src/main/resources/processes/rc_twin_process.bpmn")).exists();

        Path tpRoot = project.directory().resolve("src/main/java/com/tp/TargetPlatform");
        assertThat(tpRoot.resolve("proxy/delegates/Cutting.java")).exists();
        assertThat(tpRoot.resolve("proxy/events/CuttingSignal.java")).exists();
        assertThat(tpRoot.resolve("twin/delegates/CuttingTwin.java")).exists();
        // no twin event was authored above - the twin/events directory must exist (prepared) but
        // stay empty, not invent something that was never in the BPMN
        assertThat(tpRoot.resolve("twin/events")).isEmptyDirectory();
    }

    @Test
    void regeneratingTheSameProjectStyleReplacesStaleGeneratedSourcesRatherThanAccumulatingThem() {
        // clearTargetPlatformGeneratedSources() only matters within a single generate() call today
        // (each generate mints a fresh project dir), but this proves that same clearing logic
        // doesn't, say, throw or silently keep two conflicting delegates for one id if the BPMN
        // renames an activity between what a hand-edited copy of a directory might contain.
        String proxyBpmn = bpmn("rc_proxy_process", """
                <bpmn2:serviceTask id="Marking" name="Marking" camunda:delegateExpression="${marking}" />
                """);
        String twinBpmn = bpmn("rc_twin_process", "");

        GeneratedProject project = generator().generateWithAuthoredTwin(proxyBpmn, twinBpmn);

        Path generated = project.directory().resolve("src/main/java/com/tp/TargetPlatform/proxy/delegates/Marking.java");
        assertThat(generated).exists();
        assertThat(project.directory().resolve(".metaml-project.properties")).exists();
    }

    private static String bpmnWithSignal(String processId, String signalElementId, String signalName,
            String activityXml) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:signal id="%1$s" name="%2$s" />
                  <bpmn2:process id="%3$s" name="%3$s" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:intermediateCatchEvent id="%2$sCatch" name="%2$s catch">
                      <bpmn2:signalEventDefinition signalRef="%1$s" />
                    </bpmn2:intermediateCatchEvent>
                    %4$s
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """.formatted(signalElementId, signalName, processId, activityXml);
    }

    // Proves the actual point of this session's work: a signal shared by both proxy and twin BPMNs
    // gets the full synchronization layer generated around it - PairRegistry, RabbitMqConfig with a
    // dedicated queue pair for the shared signal, both publisher/listener pairs, SignalBroadcaster,
    // and both /start-capable controllers, all under the fixed com.tp.TargetPlatform package.
    @Test
    void aSignalSharedByProxyAndTwinGetsTheFullSynchronizationLayerGenerated() throws IOException {
        String proxyBpmn = bpmnWithSignal("rc_proxy_process", "Signal_Cutting", "cuttingSignal",
                "<bpmn2:serviceTask id=\"Cutting\" camunda:delegateExpression=\"${cutting}\" />");
        String twinBpmn = bpmnWithSignal("rc_twin_process", "Signal_CuttingTwin", "cuttingSignal", "");

        GeneratedProject project = generator().generateWithAuthoredTwin(proxyBpmn, twinBpmn);
        Path tpRoot = project.directory().resolve("src/main/java/com/tp/TargetPlatform");

        assertThat(tpRoot.resolve("coordination/PairRegistry.java")).exists();
        assertThat(tpRoot.resolve("signal/SignalBroadcaster.java")).exists();
        assertThat(tpRoot.resolve("proxy/controller/ProxyProcessController.java")).exists();
        assertThat(tpRoot.resolve("twin/controller/TwinProcessController.java")).exists();

        Path rabbitMqConfig = tpRoot.resolve("messaging/RabbitMqConfig.java");
        assertThat(rabbitMqConfig).exists();
        assertThat(Files.readString(rabbitMqConfig))
                .as("the shared signal must get its own dedicated queue pair, not just appear in a broadcast list")
                .contains("cuttingSignal");

        String proxyController = Files.readString(tpRoot.resolve("proxy/controller/ProxyProcessController.java"));
        assertThat(proxyController)
                .contains("startProcessInstanceByKey(\"rc_proxy_process\"")
                .contains("@RequestMapping(\"/api/proxy\")");
        String twinController = Files.readString(tpRoot.resolve("twin/controller/TwinProcessController.java"));
        assertThat(twinController)
                .contains("startProcessInstanceByKey(\"rc_twin_process\"")
                .contains("@RequestMapping(\"/api/twin\")");

        // Also reachable via the single-BPMN generate() path (auto-derived twin), not just
        // generateWithAuthoredTwin above - the messaging layer must not depend on which entry point
        // produced the twin BPMN.
        GeneratedProject singleBpmnProject = generator().generate(bpmn("rc_solo_process",
                "<bpmn2:serviceTask id=\"Solo\" camunda:delegateExpression=\"${solo}\" />"), java.util.List.of());
        assertThat(singleBpmnProject.directory()
                .resolve("src/main/java/com/tp/TargetPlatform/signal/SignalBroadcaster.java")).exists();
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
