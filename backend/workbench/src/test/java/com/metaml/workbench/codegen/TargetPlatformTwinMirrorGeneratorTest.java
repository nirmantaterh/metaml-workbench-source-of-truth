package com.metaml.workbench.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TargetPlatformTwinMirrorGeneratorTest {

    private final TargetPlatformTwinMirrorGenerator generator = new TargetPlatformTwinMirrorGenerator();

    private static String bpmn(String processId, String processName, String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:signal id="Signal_1" name="cuttingSignal" />
                  <bpmn2:process id="%s" name="%s" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    %s
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """.formatted(processId, processName, body);
    }

    @Test
    void suffixesTheProcessIdAndNameSoItDeploysAsADistinctDefinition() {
        String proxy = bpmn("RedCollar.Manuf", "Manuf", "");

        String twin = generator.mirror(proxy);

        assertThat(twin).contains("id=\"RedCollar.Manuf_twin\"").contains("name=\"Manuf (twin)\"");
        // the original must not have been mutated in place
        assertThat(proxy).contains("id=\"RedCollar.Manuf\"").doesNotContain("RedCollar.Manuf_twin");
    }

    @Test
    void leavesTheSharedSignalNameUntouched() {
        // signalRef and the signal's own name are the entire mechanism SignalBroadcaster uses to
        // recognize proxy and twin as synchronizing on the same point - changing either breaks it.
        String proxy = bpmn("RedCollar.Manuf", "Manuf", """
                <bpmn2:intermediateCatchEvent id="Event_Cut">
                  <bpmn2:signalEventDefinition signalRef="Signal_1" />
                </bpmn2:intermediateCatchEvent>
                """);

        String twin = generator.mirror(proxy);

        assertThat(twin)
                .contains("name=\"cuttingSignal\"")
                .contains("signalRef=\"Signal_1\"")
                // the catch event's own id is also untouched - only unique within one process
                // definition, never contended across two
                .contains("id=\"Event_Cut\"");
    }

    @Test
    void suffixesExternalTaskTopicsSoProxyAndTwinDontShareAWorkerSubscription() {
        String proxy = bpmn("RedCollar.Manuf", "Manuf", """
                <bpmn2:serviceTask id="Cutting" name="Cutting" camunda:type="external" camunda:topic="Cutting" />
                """);

        String twin = generator.mirror(proxy);

        assertThat(twin)
                .contains("camunda:topic=\"CuttingTwin\"")
                .doesNotContain("camunda:topic=\"Cutting\"/>")
                .doesNotContain("camunda:topic=\"Cutting\" ");
    }

    @Test
    void preservesEveryOtherElementAndAttributeVerbatim() {
        String proxy = bpmn("RedCollar.Manuf", "Manuf", """
                <bpmn2:exclusiveGateway id="Gateway_1" name="Quality OK?" default="Flow_no" />
                <bpmn2:serviceTask id="Cutting" name="Cutting" camunda:type="external" camunda:topic="Cutting" />
                """);

        String twin = generator.mirror(proxy);

        assertThat(twin)
                .contains("id=\"Gateway_1\"")
                .contains("name=\"Quality OK?\"")
                .contains("default=\"Flow_no\"")
                .contains("id=\"Cutting\"")
                .contains("name=\"Cutting\"");
    }
}
