package com.metaml.workbench.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

// Runs against the actual RedCollar BPMNs the professor supplied. RedCollar is the validation case
// for external-task-worker generation, not a fixture to be simplified: its processes are built
// entirely from camunda:type="external" tasks, which is exactly the shape DelegateClassGenerator
// produces nothing for.
//
// The BPMNs are not committed to the repository (professor-supplied, not ours to redistribute) -
// resolved from redcollar.bpmn.dir / REDCOLLAR_BPMN_DIR / the repo root, same convention as
// RedCollarEndToEndTest. Skips (not fails) when neither file is found there.
class ExternalTaskWorkerGeneratorTest {

    private static final Path REPO_ROOT = redCollarBpmnDir();

    private final ExternalTaskWorkerGenerator generator = new ExternalTaskWorkerGenerator();

    private static Path redCollarBpmnDir() {
        String configured = System.getProperty("redcollar.bpmn.dir", System.getenv("REDCOLLAR_BPMN_DIR"));
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of("../..");
    }

    private static void assumeFixturesPresent() {
        Assumptions.assumeTrue(Files.isRegularFile(REPO_ROOT.resolve("Manuf-camunda.bpmn"))
                        && Files.isRegularFile(REPO_ROOT.resolve("Twin-camunda.bpmn")),
                "RedCollar BPMNs not found at " + REPO_ROOT.toAbsolutePath()
                        + " - point redcollar.bpmn.dir or REDCOLLAR_BPMN_DIR at the professor-supplied files");
    }

    @Test
    void generatesOneMlSimulatingWorkerPerTwinTopic() throws Exception {
        assumeFixturesPresent();
        String twinBpmn = Files.readString(REPO_ROOT.resolve("Twin-camunda.bpmn"));

        List<GeneratedWorker> workers = generator.generate(twinBpmn, "com.metaml.targetplatform.twin.worker", true);

        assertThat(workers).extracting(GeneratedWorker::topic)
                .containsExactly("OrderMgmtInitializationTwin", "SamplingTwin", "MarkingTwin", "CuttingTwin",
                        "StitchingTwin", "CheckingTwin", "PressingTwin", "PackagingTwin", "ShippingTwin", "LayingTwin");

        GeneratedWorker sampling = workers.stream().filter(w -> w.topic().equals("SamplingTwin")).findFirst()
                .orElseThrow();
        assertThat(sampling.className()).isEqualTo("SamplingTwinWorker");
        assertThat(sampling.sourceCode())
                .contains("package com.metaml.targetplatform.twin.worker;")
                .contains("implements GeneratedExternalTaskWorker")
                .contains("return \"SamplingTwin\"")
                .contains("[Twin] Invoking simulated ML agent")
                .contains("simulateAgentInvocation(task)")
                .contains("externalTaskService.complete(task.getId(), \"generated-worker\", agentResult)")
                .contains("UUID.randomUUID()")
                .contains("agentTimestamp")
                .doesNotContain("\"PASS\"")
                .doesNotContain("\"FAIL\"");
    }

    @Test
    void generatesPlainCompletingWorkerPerManufacturingTopic() throws Exception {
        assumeFixturesPresent();
        String manufBpmn = Files.readString(REPO_ROOT.resolve("Manuf-camunda.bpmn"));

        List<GeneratedWorker> workers = generator.generate(manufBpmn, "com.metaml.targetplatform.manuf.worker", false);

        assertThat(workers).extracting(GeneratedWorker::topic)
                .contains("OrderMgmtInitialization", "VerifyOrder", "EditOrderDetails", "Sampling", "Marking",
                        "Cutting", "Laying", "Stitching", "Checking", "Pressing", "Packaging", "Shipping")
                .doesNotContain("SamplingTwin");

        // Non-gateway worker: plain completion, no variables map
        GeneratedWorker sampling = workers.stream().filter(w -> w.topic().equals("Sampling")).findFirst()
                .orElseThrow();
        assertThat(sampling.sourceCode())
                .contains("implements GeneratedExternalTaskWorker")
                .contains("return \"Sampling\"")
                .contains("Executing generated external-task worker")
                .contains("externalTaskService.complete(task.getId(), \"generated-worker\")")
                .doesNotContain("simulated ML agent")
                .doesNotContain("agentResult")
                .doesNotContain("Math.random()");
    }

    @Test
    void gatewayPrecedingWorkerSetsConditionVariables() throws Exception {
        assumeFixturesPresent();
        String manufBpmn = Files.readString(REPO_ROOT.resolve("Manuf-camunda.bpmn"));

        List<GeneratedWorker> workers = generator.generate(manufBpmn, "com.metaml.targetplatform.manuf.worker", false);

        // VerifyOrder immediately precedes the gateway checking ${orderApproved}
        GeneratedWorker verifyOrder = workers.stream().filter(w -> w.topic().equals("VerifyOrder")).findFirst()
                .orElseThrow();
        assertThat(verifyOrder.sourceCode())
                .contains("variables.put(\"orderApproved\"")
                .contains("Math.random()")
                .contains("externalTaskService.complete(task.getId(), \"generated-worker\", variables)")
                .doesNotContain("\"PASS\"")
                .doesNotContain("\"FAIL\"");

        // Checking immediately precedes the gateway checking ${qualityPassed}
        GeneratedWorker checking = workers.stream().filter(w -> w.topic().equals("Checking")).findFirst()
                .orElseThrow();
        assertThat(checking.sourceCode())
                .contains("variables.put(\"qualityPassed\"")
                .contains("Math.random()")
                .contains("externalTaskService.complete(task.getId(), \"generated-worker\", variables)");
    }

    @Test
    void detectsGatewayVariablesFromManufacturingBpmn() throws Exception {
        assumeFixturesPresent();
        String manufBpmn = Files.readString(REPO_ROOT.resolve("Manuf-camunda.bpmn"));
        BpmnModelInstance model = Bpmn.readModelFromStream(
                new ByteArrayInputStream(manufBpmn.getBytes(StandardCharsets.UTF_8)));

        Map<String, Set<String>> gatewayVars = ExternalTaskWorkerGenerator.detectGatewayVariables(model);

        // VerifyOrder (topic) → orderApproved (gateway condition)
        assertThat(gatewayVars).containsKey("VerifyOrder");
        assertThat(gatewayVars.get("VerifyOrder")).contains("orderApproved");

        // Checking (topic) → qualityPassed (gateway condition)
        assertThat(gatewayVars).containsKey("Checking");
        assertThat(gatewayVars.get("Checking")).contains("qualityPassed");

        // Other topics do not precede gateways
        assertThat(gatewayVars).doesNotContainKey("Sampling");
        assertThat(gatewayVars).doesNotContainKey("OrderMgmtInitialization");
    }

    @Test
    void twinBpmnHasNoGatewayVariables() throws Exception {
        assumeFixturesPresent();
        String twinBpmn = Files.readString(REPO_ROOT.resolve("Twin-camunda.bpmn"));
        BpmnModelInstance model = Bpmn.readModelFromStream(
                new ByteArrayInputStream(twinBpmn.getBytes(StandardCharsets.UTF_8)));

        Map<String, Set<String>> gatewayVars = ExternalTaskWorkerGenerator.detectGatewayVariables(model);

        assertThat(gatewayVars).isEmpty();
    }
}
