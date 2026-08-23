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
    void generatesOneAgentDelegatingWorkerPerTwinTopic() throws Exception {
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
                // The decision is delegated to an OPTIONALLY injected, pluggable TwinDecisionAgent -
                // not hardcoded inline - so a real model/agent can be wired in with no generated
                // code to touch (see SpringBootProjectGenerator.writeTwinDecisionAgentInterface).
                // ObjectProvider (not TwinDecisionAgent directly) is what makes zero implementations
                // a safe, non-fatal case - see renderTwinWorkerSource's own comment for why.
                .contains("private final ObjectProvider<TwinDecisionAgent> agentProvider")
                .contains("public SamplingTwinWorker(ObjectProvider<TwinDecisionAgent> agentProvider)")
                .contains("agentProvider.getIfAvailable()")
                .contains("[Twin] Invoking decision agent")
                .contains("agent.decide(\"SamplingTwin\", task)")
                // ...and the built-in fallback still exists for when nobody has registered one.
                .contains("No TwinDecisionAgent registered")
                .contains("externalTaskService.complete(task.getId(), \"generated-worker\", variables)")
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

    // The bug this exists to catch: RedCollar's own hand-authored Twin-camunda.bpmn happens to have
    // no gateways at all (see twinBpmnHasNoGatewayVariables above), so the RedCollar fixture tests
    // never exercised a twin worker that precedes an exclusive gateway. TargetPlatformTwinMirrorGenerator
    // auto-derives a twin by preserving the proxy's gateway structure verbatim (only topics get a
    // "Twin" suffix), so a mirrored twin worker CAN precede a gateway - and simulateMlAgent=true
    // workers were rendered from a template that never wrote the condition variable at all, so the
    // gateway threw "Cannot resolve identifier" the moment a real instance reached it. Regression
    // test for that: this incident was only found live, in Cockpit, on a real generated
    // RedCollarTP instance - not by any prior automated test.
    @Test
    void twinWorkerPrecedingAGatewaySetsTheSameConditionVariablesAsTheProxyWorkerWould() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="rc_twin_process" name="Process_twin" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:sequenceFlow id="f1" sourceRef="Start" targetRef="VerifyOrderTwin" />
                    <bpmn2:serviceTask id="VerifyOrderTwin" name="Verify Order Details"
                        camunda:type="external" camunda:topic="VerifyOrderTwin" />
                    <bpmn2:sequenceFlow id="f2" sourceRef="VerifyOrderTwin" targetRef="Gateway_1" />
                    <bpmn2:exclusiveGateway id="Gateway_1">
                      <bpmn2:incoming>f2</bpmn2:incoming>
                      <bpmn2:outgoing>f3</bpmn2:outgoing>
                      <bpmn2:outgoing>f4</bpmn2:outgoing>
                    </bpmn2:exclusiveGateway>
                    <bpmn2:sequenceFlow id="f3" sourceRef="Gateway_1" targetRef="End1">
                      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${!orderApproved}</bpmn2:conditionExpression>
                    </bpmn2:sequenceFlow>
                    <bpmn2:sequenceFlow id="f4" sourceRef="Gateway_1" targetRef="End2">
                      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${orderApproved}</bpmn2:conditionExpression>
                    </bpmn2:sequenceFlow>
                    <bpmn2:endEvent id="End1" />
                    <bpmn2:endEvent id="End2" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;

        List<GeneratedWorker> workers = generator.generate(xml, "com.tp.TargetPlatform.worker.twin", true);

        GeneratedWorker verifyOrderTwin = workers.stream().filter(w -> w.topic().equals("VerifyOrderTwin"))
                .findFirst().orElseThrow();
        assertThat(verifyOrderTwin.sourceCode())
                .contains("[Twin] Invoking decision agent")
                .contains("agent.decide(\"VerifyOrderTwin\", task)")
                // The fallback only fires when the agent itself didn't set the variable - a real
                // TwinDecisionAgent implementation that DOES return "orderApproved" has that value
                // win, since containsKey is false only when the agent left it out.
                .contains("if (!variables.containsKey(\"orderApproved\")) {")
                .contains("variables.put(\"orderApproved\", Math.random() > 0.5);")
                .contains("externalTaskService.complete(task.getId(), \"generated-worker\", variables)");
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
