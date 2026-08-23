package com.metaml.workbench.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

// Unit-level proof of the actual "scan for delegates and generate classes" mechanism the
// TargetPlatform generate path depends on (see SpringBootProjectGenerator.generateTargetPlatform):
// both serviceTask activities AND *Event elements (camunda:delegateExpression or camunda:class)
// must be discovered, on both a proxy and a twin BPMN, each landing in its own
// proxy|twin / delegates|events directory with a normalised delegateExpression bean reference.
class TargetPlatformSourceGeneratorTest {

    private final TargetPlatformSourceGenerator generator = new TargetPlatformSourceGenerator();

    private static String bpmn(String activityXml) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="rc_proxy_process" name="Process_proxy" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    %s
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """.formatted(activityXml);
    }

    @Test
    void discoversAServiceTaskWithADelegateExpressionAsAProxyDelegate() {
        String xml = bpmn("""
                <bpmn2:serviceTask id="Cutting" name="Cutting"
                    camunda:delegateExpression="${cutting}" />
                """);

        TargetPlatformSourceGenerator.Result result = generator.generate(xml, false);

        assertThat(result.sources()).hasSize(1);
        TargetPlatformSourceGenerator.GeneratedSource source = result.sources().get(0);
        assertThat(source.relativeDirectory()).isEqualTo("proxy/delegates");
        assertThat(source.className()).isEqualTo("Cutting");
        assertThat(source.source())
                .contains("package com.tp.TargetPlatform.proxy.delegates;")
                .contains("@Component(\"cutting\")")
                .contains("public class Cutting implements JavaDelegate");
        // the bean reference must stay in sync with the id above, whatever it's named
        assertThat(result.bpmnXml()).contains("camunda:delegateExpression=\"${cutting}\"");
    }

    @Test
    void normalisesACamundaClassActivityToADelegateExpressionBean() {
        String xml = bpmn("""
                <bpmn2:serviceTask id="Stitching" name="Stitching"
                    camunda:class="com.legacy.StitchingHandler" />
                """);

        TargetPlatformSourceGenerator.Result result = generator.generate(xml, false);

        assertThat(result.sources()).hasSize(1);
        assertThat(result.sources().get(0).className()).isEqualTo("Stitching");
        // camunda:class must be gone, replaced by a delegateExpression the generated bean satisfies
        assertThat(result.bpmnXml())
                .doesNotContain("camunda:class")
                .contains("camunda:delegateExpression=\"${stitching}\"");
    }

    @Test
    void discoversASignalCatchEventWithADelegateExpressionAsATwinEvent() {
        String xml = bpmn("""
                <bpmn2:intermediateCatchEvent id="SamplingSignal" name="Sampling Signal"
                    camunda:delegateExpression="${samplingSignal}">
                  <bpmn2:signalEventDefinition />
                </bpmn2:intermediateCatchEvent>
                """);

        TargetPlatformSourceGenerator.Result result = generator.generate(xml, true);

        assertThat(result.sources()).hasSize(1);
        TargetPlatformSourceGenerator.GeneratedSource source = result.sources().get(0);
        assertThat(source.relativeDirectory()).isEqualTo("twin/events");
        assertThat(source.className()).isEqualTo("SamplingSignal");
        assertThat(source.source())
                .contains("package com.tp.TargetPlatform.twin.events;")
                .contains("TWIN (MSG)");
    }

    @Test
    void anActivityAndAnEventInTheSameBpmnBothGetGenerated() {
        String xml = bpmn("""
                <bpmn2:serviceTask id="Marking" name="Marking"
                    camunda:delegateExpression="${marking}" />
                <bpmn2:intermediateCatchEvent id="MarkingSignal" name="Marking Signal"
                    camunda:delegateExpression="${markingSignal}">
                  <bpmn2:signalEventDefinition />
                </bpmn2:intermediateCatchEvent>
                """);

        TargetPlatformSourceGenerator.Result result = generator.generate(xml, false);

        List<String> directories = result.sources().stream()
                .map(TargetPlatformSourceGenerator.GeneratedSource::relativeDirectory).toList();
        assertThat(directories).containsExactlyInAnyOrder("proxy/delegates", "proxy/events");
    }

    @Test
    void anExecutionListenerDelegateExpressionGetsAStubBeanEvenThoughItsNotOnTheActivityItself() {
        String xml = bpmn("""
                <bpmn2:serviceTask id="Cutting" name="Cutting" camunda:type="external" camunda:topic="Cutting">
                  <bpmn2:extensionElements>
                    <camunda:executionListener event="end" delegateExpression="${manufTaskCompletionListener}" />
                  </bpmn2:extensionElements>
                </bpmn2:serviceTask>
                """);

        TargetPlatformSourceGenerator.Result result = generator.generate(xml, false);

        // the external task itself has no delegateExpression/class of its own, so it must not
        // produce a delegates/events entry - only the listener bean is generated
        assertThat(result.sources()).hasSize(1);
        TargetPlatformSourceGenerator.GeneratedSource source = result.sources().get(0);
        assertThat(source.relativeDirectory()).isEqualTo("proxy/listeners");
        assertThat(source.className()).isEqualTo("ManufTaskCompletionListener");
        assertThat(source.source())
                .contains("package com.tp.TargetPlatform.proxy.listeners;")
                .contains("@Component(\"manufTaskCompletionListener\")")
                .contains("implements ExecutionListener");
        // the BPMN itself is left untouched on the proxy side - unlike the activity/event rewrite
        // above, a listener reference is already a clean bean name and needs no normalisation
        assertThat(result.bpmnXml()).contains("delegateExpression=\"${manufTaskCompletionListener}\"");
    }

    // The bug this exists to catch: a Twin structurally mirrored from its proxy (see
    // TargetPlatformTwinMirrorGenerator) carries the exact same executionListener reference the
    // proxy has. Without renaming, scanning both sides would generate two DIFFERENT classes
    // registered under the identical Spring bean name "manufTaskCompletionListener" - proxy's own
    // and twin's own - and the application would fail to start at all.
    @Test
    void theTwinSidesListenerBeanIsRenamedSoItNeverCollidesWithTheProxysOwnBeanOfTheSameOriginalName() {
        String xml = bpmn("""
                <bpmn2:serviceTask id="CuttingTwin" name="Cutting Twin" camunda:type="external" camunda:topic="CuttingTwin">
                  <bpmn2:extensionElements>
                    <camunda:executionListener event="end" delegateExpression="${manufTaskCompletionListener}" />
                  </bpmn2:extensionElements>
                </bpmn2:serviceTask>
                """);

        TargetPlatformSourceGenerator.Result result = generator.generate(xml, true);

        assertThat(result.sources()).hasSize(1);
        TargetPlatformSourceGenerator.GeneratedSource source = result.sources().get(0);
        assertThat(source.relativeDirectory()).isEqualTo("twin/listeners");
        assertThat(source.className()).isEqualTo("ManufTaskCompletionListenerTwin");
        assertThat(source.source())
                .contains("package com.tp.TargetPlatform.twin.listeners;")
                .contains("@Component(\"manufTaskCompletionListenerTwin\")");
        // the twin BPMN IS rewritten (unlike the proxy-side case above) - it has to stop pointing
        // at the bare original name, which only the proxy's own bean is now registered under
        assertThat(result.bpmnXml())
                .doesNotContain("delegateExpression=\"${manufTaskCompletionListener}\"")
                .contains("delegateExpression=\"${manufTaskCompletionListenerTwin}\"");
    }

    @Test
    void theSameExecutionListenerBeanReferencedByManyActivitiesOnlyGeneratesOneClass() {
        String xml = bpmn("""
                <bpmn2:serviceTask id="Cutting" name="Cutting" camunda:type="external" camunda:topic="Cutting">
                  <bpmn2:extensionElements>
                    <camunda:executionListener event="end" delegateExpression="${manufTaskCompletionListener}" />
                  </bpmn2:extensionElements>
                </bpmn2:serviceTask>
                <bpmn2:serviceTask id="Marking" name="Marking" camunda:type="external" camunda:topic="Marking">
                  <bpmn2:extensionElements>
                    <camunda:executionListener event="end" delegateExpression="${manufTaskCompletionListener}" />
                  </bpmn2:extensionElements>
                </bpmn2:serviceTask>
                """);

        TargetPlatformSourceGenerator.Result result = generator.generate(xml, false);

        assertThat(result.sources()).hasSize(1);
    }

    @Test
    void anElementWithNeitherClassNorDelegateExpressionIsSkipped() {
        String xml = bpmn("""
                <bpmn2:serviceTask id="Plain" name="Plain, no implementation" />
                """);

        TargetPlatformSourceGenerator.Result result = generator.generate(xml, false);

        assertThat(result.sources()).isEmpty();
    }
}
