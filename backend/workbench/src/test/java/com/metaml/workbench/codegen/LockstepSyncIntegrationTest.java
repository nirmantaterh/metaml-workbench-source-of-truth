package com.metaml.workbench.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.Test;

import com.metaml.workbench.bpmn.TwinModelGenerator;

/**
 * Proves that the lockstep synchronization mechanism correctly transforms both
 * proxy and twin BPMNs for delegate-expression workflows (the Fried Rice case):
 *
 *   1. Proxy BPMN: signal catch events inserted after each serviceTask
 *   2. Twin BPMN: receiveTask elements replaced by signal catch events
 *   3. Same signal names on both sides (sync_<activityId>)
 *   4. TwinAdvance_ message declarations removed from twin
 *   5. Signal declarations added to both BPMNs
 */
class LockstepSyncIntegrationTest {

    private final TargetPlatformSourceGenerator generator = new TargetPlatformSourceGenerator();

    // Mimics the Fried Rice BPMN: 3 delegate-expression serviceTasks in sequence
    private static final String PROXY_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                id="Definitions_1" targetNamespace="http://metaml.com/bpmn">
              <bpmn:process id="Process_1" isExecutable="true" name="Test Process">
                <bpmn:startEvent id="start">
                  <bpmn:outgoing>Flow_1</bpmn:outgoing>
                </bpmn:startEvent>
                <bpmn:sequenceFlow id="Flow_1" sourceRef="start" targetRef="taskA"/>
                <bpmn:serviceTask id="taskA" name="Task A" camunda:delegateExpression="${taskA}">
                  <bpmn:incoming>Flow_1</bpmn:incoming>
                  <bpmn:outgoing>Flow_2</bpmn:outgoing>
                </bpmn:serviceTask>
                <bpmn:sequenceFlow id="Flow_2" sourceRef="taskA" targetRef="taskB"/>
                <bpmn:serviceTask id="taskB" name="Task B" camunda:delegateExpression="${taskB}">
                  <bpmn:incoming>Flow_2</bpmn:incoming>
                  <bpmn:outgoing>Flow_3</bpmn:outgoing>
                </bpmn:serviceTask>
                <bpmn:sequenceFlow id="Flow_3" sourceRef="taskB" targetRef="taskC"/>
                <bpmn:serviceTask id="taskC" name="Task C" camunda:delegateExpression="${taskC}">
                  <bpmn:incoming>Flow_3</bpmn:incoming>
                  <bpmn:outgoing>Flow_4</bpmn:outgoing>
                </bpmn:serviceTask>
                <bpmn:sequenceFlow id="Flow_4" sourceRef="taskC" targetRef="end"/>
                <bpmn:endEvent id="end">
                  <bpmn:incoming>Flow_4</bpmn:incoming>
                </bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
            """;

    @Test
    void proxyBpmnGetsSignalCatchEventsAfterEachServiceTask() {
        TargetPlatformSourceGenerator.Result result = generator.generate(PROXY_BPMN, false);

        assertThat(result.syncSignalNames())
                .containsExactlyInAnyOrder("sync_taskA", "sync_taskB", "sync_taskC");
        assertThat(result.syncActivityIds())
                .containsExactlyInAnyOrder("taskA", "taskB", "taskC");

        // The XSD-ordering bugs found during manual runtime validation (incoming/outgoing vs.
        // signalEventDefinition; signal vs. BPMNDiagram) were both invisible to the string-contains
        // assertions below - only a real schema-validating parse ever caught them. Re-parsing the
        // transformed output through Camunda's own BPMN parser here means a THIRD such regression
        // fails this test instead of only surfacing at deployment time.
        BpmnModelInstance parsedProxy = Bpmn.readModelFromStream(
                new ByteArrayInputStream(result.bpmnXml().getBytes(StandardCharsets.UTF_8)));
        assertThat(parsedProxy).as("transformed proxy BPMN must remain valid, parseable Camunda BPMN").isNotNull();

        String xml = result.bpmnXml();
        // Signal catch events inserted
        assertThat(xml).contains("sync_evt_taskA");
        assertThat(xml).contains("sync_evt_taskB");
        assertThat(xml).contains("sync_evt_taskC");
        // Signal declarations added
        assertThat(xml).contains("name=\"sync_taskA\"");
        assertThat(xml).contains("name=\"sync_taskB\"");
        assertThat(xml).contains("name=\"sync_taskC\"");
        // Bridge flows created (serviceTask → catch event)
        assertThat(xml).contains("sync_flow_taskA");
        assertThat(xml).contains("sync_flow_taskB");
        assertThat(xml).contains("sync_flow_taskC");
        // Original flows redirected through catch events
        assertThat(xml).contains("sourceRef=\"sync_evt_taskA\"");
        assertThat(xml).contains("sourceRef=\"sync_evt_taskB\"");
        assertThat(xml).contains("sourceRef=\"sync_evt_taskC\"");
    }

    @Test
    void twinBpmnReceiveTasksReplacedWithSignalCatchEvents() {
        // First generate proxy to get sync activity IDs
        TargetPlatformSourceGenerator.Result proxyResult = generator.generate(PROXY_BPMN, false);

        // Generate twin BPMN via TwinModelGenerator (same as generateTargetPlatform does)
        BpmnModelInstance proxyModel = Bpmn.readModelFromStream(new ByteArrayInputStream(PROXY_BPMN.getBytes(StandardCharsets.UTF_8)));
        TwinModelGenerator twinGen = new TwinModelGenerator();
        String twinBpmn = Bpmn.convertToString(twinGen.generate(proxyModel));

        // Before transformation: twin has receiveTask + TwinAdvance messages
        assertThat(twinBpmn).contains("receiveTask");
        assertThat(twinBpmn).contains("TwinAdvance_taskA");

        // Transform twin with sync activity IDs from proxy
        TargetPlatformSourceGenerator.Result twinResult = generator.generate(
                twinBpmn, true, proxyResult.syncActivityIds());

        // Same schema-validity guard as the proxy side above - the twin transformation does its own
        // independent element-ordering surgery (replacing receiveTask with intermediateCatchEvent)
        // and deserves the same regression net.
        BpmnModelInstance parsedTwin = Bpmn.readModelFromStream(
                new ByteArrayInputStream(twinResult.bpmnXml().getBytes(StandardCharsets.UTF_8)));
        assertThat(parsedTwin).as("transformed twin BPMN must remain valid, parseable Camunda BPMN").isNotNull();

        String xml = twinResult.bpmnXml();
        // receiveTask elements replaced with intermediateCatchEvent
        assertThat(xml).doesNotContain("receiveTask");
        // TwinAdvance messages removed
        assertThat(xml).doesNotContain("TwinAdvance_");
        // Signal declarations added (same names as proxy)
        assertThat(xml).contains("name=\"sync_taskA\"");
        assertThat(xml).contains("name=\"sync_taskB\"");
        assertThat(xml).contains("name=\"sync_taskC\"");
        // signalEventDefinition present
        assertThat(xml).contains("signalEventDefinition");
        // _automate serviceTasks still present (the delegates)
        assertThat(xml).contains("taskA_automate");
        assertThat(xml).contains("taskB_automate");
        assertThat(xml).contains("taskC_automate");

        assertThat(twinResult.syncSignalNames())
                .containsExactlyInAnyOrder("sync_taskA", "sync_taskB", "sync_taskC");
    }

    @Test
    void signalNamesMatchBetweenProxyAndTwinBpmns() {
        TargetPlatformSourceGenerator.Result proxyResult = generator.generate(PROXY_BPMN, false);

        BpmnModelInstance proxyModel = Bpmn.readModelFromStream(new ByteArrayInputStream(PROXY_BPMN.getBytes(StandardCharsets.UTF_8)));
        String twinBpmn = Bpmn.convertToString(new TwinModelGenerator().generate(proxyModel));
        TargetPlatformSourceGenerator.Result twinResult = generator.generate(
                twinBpmn, true, proxyResult.syncActivityIds());

        // The entire point: both sides use the exact same signal names
        assertThat(proxyResult.syncSignalNames()).isEqualTo(twinResult.syncSignalNames());
    }

    @Test
    void externalTasksAreNotAffectedBySyncSignalInsertion() {
        String bpmnWithExternalTask = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn:process id="P1" isExecutable="true">
                    <bpmn:startEvent id="S" />
                    <bpmn:serviceTask id="ExtTask" camunda:type="external" camunda:topic="myTopic" />
                    <bpmn:endEvent id="E" />
                  </bpmn:process>
                </bpmn:definitions>
                """;

        TargetPlatformSourceGenerator.Result result = generator.generate(bpmnWithExternalTask, false);

        // No sync signals generated for external tasks (they don't have delegateExpression/class)
        assertThat(result.syncSignalNames()).isEmpty();
        assertThat(result.syncActivityIds()).isEmpty();
    }
}
