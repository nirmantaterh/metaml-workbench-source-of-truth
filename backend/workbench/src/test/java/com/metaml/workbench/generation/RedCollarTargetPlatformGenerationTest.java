package com.metaml.workbench.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.codegen.DelegateClassGenerator;
import com.metaml.workbench.codegen.ExternalTaskWorkerGenerator;

class RedCollarTargetPlatformGenerationTest {

    @TempDir
    Path tempDir;

    private static final Path REAL_TEMPLATE = Path.of("../RedCollarTP");
    private static final Path MANUF_BPMN = Path.of("../../Manuf-camunda.bpmn");
    private static final Path TWIN_BPMN = Path.of("../../Twin-camunda.bpmn");

    @Test
    void generatesCanonicalRedCollarTargetPlatformFromAuthoredBpmans() throws IOException {
        assertThat(Files.isDirectory(REAL_TEMPLATE))
                .as("RedCollarTP template must exist at %s", REAL_TEMPLATE.toAbsolutePath()).isTrue();
        assertThat(Files.isRegularFile(MANUF_BPMN))
                .as("Manuf-camunda.bpmn must exist at %s", MANUF_BPMN.toAbsolutePath()).isTrue();
        assertThat(Files.isRegularFile(TWIN_BPMN))
                .as("Twin-camunda.bpmn must exist at %s", TWIN_BPMN.toAbsolutePath()).isTrue();

        String manufBpmnXml = Files.readString(MANUF_BPMN, StandardCharsets.UTF_8);
        String twinBpmnXml = Files.readString(TWIN_BPMN, StandardCharsets.UTF_8);

        Path outputDir = tempDir.resolve("generated-target-platforms");
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(
                REAL_TEMPLATE.toString(),
                outputDir.toString(),
                new TwinModelGenerator(),
                new DelegateClassGenerator(),
                new ExternalTaskWorkerGenerator());

        GeneratedProject project = generator.generateWithAuthoredTwin(manufBpmnXml, twinBpmnXml, "redcollar-manufacturing");

        assertThat(project).isNotNull();
        assertThat(project.directory()).exists();
        assertThat(project.processKey()).isEqualTo("RedCollar.Manuf");

        Path tpRoot = project.directory().resolve("src/main/java/com/tp/TargetPlatform");

        // 1. Verify Core Coordination & Messaging
        assertThat(tpRoot.resolve("coordination/PairRegistry.java")).exists();
        assertThat(tpRoot.resolve("messaging/RabbitMqConfig.java")).exists();
        assertThat(tpRoot.resolve("messaging/TaskQueueListener.java")).exists();
        assertThat(tpRoot.resolve("messaging/TaskQueuePublisher.java")).exists();
        assertThat(tpRoot.resolve("messaging/ResponseQueueListener.java")).exists();
        assertThat(tpRoot.resolve("messaging/ResponseQueuePublisher.java")).exists();
        assertThat(tpRoot.resolve("signal/SignalBroadcaster.java")).exists();

        // 2. Verify Controllers
        assertThat(tpRoot.resolve("proxy/controller/ProxyProcessController.java")).exists();
        assertThat(tpRoot.resolve("twin/controller/TwinProcessController.java")).exists();
        assertThat(tpRoot.resolve("status/GeneratedProcessStatusController.java")).exists();

        // 3. Verify External Task Workers (Proxy & Twin)
        assertThat(tpRoot.resolve("worker/proxy/CuttingWorker.java")).exists();
        assertThat(tpRoot.resolve("worker/proxy/LayingWorker.java")).exists();
        assertThat(tpRoot.resolve("worker/proxy/StitchingWorker.java")).exists();
        assertThat(tpRoot.resolve("worker/proxy/SamplingWorker.java")).exists();
        assertThat(tpRoot.resolve("worker/proxy/CheckingWorker.java")).exists();
        assertThat(tpRoot.resolve("worker/proxy/MarkingWorker.java")).exists();
        assertThat(tpRoot.resolve("worker/proxy/PackagingWorker.java")).exists();
        assertThat(tpRoot.resolve("worker/proxy/PressingWorker.java")).exists();
        assertThat(tpRoot.resolve("worker/proxy/ShippingWorker.java")).exists();

        assertThat(tpRoot.resolve("worker/twin/CuttingTwinWorker.java")).exists();
        assertThat(tpRoot.resolve("worker/twin/LayingTwinWorker.java")).exists();
        assertThat(tpRoot.resolve("worker/twin/StitchingTwinWorker.java")).exists();
        assertThat(tpRoot.resolve("worker/twin/SamplingTwinWorker.java")).exists();
        assertThat(tpRoot.resolve("worker/twin/CheckingTwinWorker.java")).exists();
        assertThat(tpRoot.resolve("worker/twin/MarkingTwinWorker.java")).exists();
        assertThat(tpRoot.resolve("worker/twin/PackagingTwinWorker.java")).exists();
        assertThat(tpRoot.resolve("worker/twin/PressingTwinWorker.java")).exists();
        assertThat(tpRoot.resolve("worker/twin/ShippingTwinWorker.java")).exists();

        // 4. Verify Process Definitions
        Path processesDir = project.directory().resolve("src/main/resources/processes");
        assertThat(processesDir.resolve("RedCollar.Manuf.bpmn")).exists();
        assertThat(processesDir.resolve("RedCollar.Twin.bpmn")).exists();

        // 5. Verify Metadata
        Path propertiesFile = project.directory().resolve(".metaml-project.properties");
        assertThat(propertiesFile).exists();
        String propertiesContent = Files.readString(propertiesFile);
        assertThat(propertiesContent).contains("processKey=RedCollar.Manuf");
        assertThat(propertiesContent).contains("displayName=redcollar-manufacturing");
    }
}
