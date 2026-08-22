package com.metaml.workbench.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class TargetPlatformMessagingGeneratorTest {

    private final TargetPlatformMessagingGenerator generator = new TargetPlatformMessagingGenerator();

    private TargetPlatformMessagingGenerator.GeneratedSource find(
            List<TargetPlatformMessagingGenerator.GeneratedSource> sources, String className) {
        return sources.stream().filter(s -> s.className().equals(className)).findFirst()
                .orElseThrow(() -> new AssertionError(className + " was not generated; got "
                        + sources.stream().map(TargetPlatformMessagingGenerator.GeneratedSource::className).toList()));
    }

    @Test
    void generatesTheWholeSynchronizationLayerPlusBothControllers() {
        List<TargetPlatformMessagingGenerator.GeneratedSource> sources = generator.generate("proj.abc123",
                Set.of("cuttingSignal"), Set.of("cuttingSignal", "orderVerifySignal"), "rc_proxy_process",
                "rc_twin_process");

        assertThat(sources.stream().map(TargetPlatformMessagingGenerator.GeneratedSource::className))
                .containsExactlyInAnyOrder("PairRegistry", "RabbitMqConfig", "TaskQueuePublisher",
                        "TaskQueueListener", "ResponseQueuePublisher", "ResponseQueueListener", "SignalBroadcaster",
                        "ProxyProcessController", "TwinProcessController");

        assertThat(find(sources, "PairRegistry").relativeDirectory()).isEqualTo("coordination");
        assertThat(find(sources, "RabbitMqConfig").relativeDirectory()).isEqualTo("messaging");
        assertThat(find(sources, "SignalBroadcaster").relativeDirectory()).isEqualTo("signal");
        assertThat(find(sources, "ProxyProcessController").relativeDirectory()).isEqualTo("proxy/controller");
        assertThat(find(sources, "TwinProcessController").relativeDirectory()).isEqualTo("twin/controller");
    }

    @Test
    void onlySharedSignalsGetADedicatedQueuePairNotEverySignal() {
        List<TargetPlatformMessagingGenerator.GeneratedSource> sources = generator.generate("proj.abc123",
                Set.of("cuttingSignal"), Set.of("cuttingSignal", "orderVerifySignal"), "rc_proxy_process",
                "rc_twin_process");
        String config = find(sources, "RabbitMqConfig").source();

        assertThat(config)
                .as("the shared signal must have its own task/response queue pair")
                .contains("Map.entry(\"cuttingSignal\", \"proj.abc123.sync.cutting-signal\")")
                .contains("Map.entry(\"cuttingSignal\", \"proj.abc123.sync.responses.cutting-signal\")");
        assertThat(config)
                .as("a signal declared on only one side must not get a queue - it falls back to direct delivery")
                .doesNotContain("orderVerifySignal");
    }

    @Test
    void signalBroadcasterListsEverySignalFromEitherSideNotJustTheSharedOnes() {
        List<TargetPlatformMessagingGenerator.GeneratedSource> sources = generator.generate("proj.abc123",
                Set.of("cuttingSignal"), Set.of("cuttingSignal", "orderVerifySignal"), "rc_proxy_process",
                "rc_twin_process");
        String broadcaster = find(sources, "SignalBroadcaster").source();

        assertThat(broadcaster).contains("\"cuttingSignal\"").contains("\"orderVerifySignal\"");
    }

    @Test
    void withNoSharedSignalsAtAllTheQueueMapsAreEmptyButEverythingStillCompiles() {
        List<TargetPlatformMessagingGenerator.GeneratedSource> sources = generator.generate("proj.xyz",
                Set.of(), Set.of(), "rc_proxy_process", "rc_twin_process");

        String taskListener = find(sources, "TaskQueueListener").source();
        assertThat(taskListener)
                .as("no shared signal means nothing to bind a RabbitListener to")
                .doesNotContain("@RabbitListener");
    }

    @Test
    void bothControllersStartByBusinessKeyAndRegisterWithPairRegistry() {
        List<TargetPlatformMessagingGenerator.GeneratedSource> sources = generator.generate("proj.abc123",
                Set.of("cuttingSignal"), Set.of("cuttingSignal"), "rc_proxy_process", "rc_twin_process");

        String proxy = find(sources, "ProxyProcessController").source();
        assertThat(proxy)
                .contains("@RequestMapping(\"/api/proxy\")")
                .contains("runtimeService.startProcessInstanceByKey(\"rc_proxy_process\", key)")
                .contains("pairRegistry.registerAndClassify");

        String twin = find(sources, "TwinProcessController").source();
        assertThat(twin)
                .contains("@RequestMapping(\"/api/twin\")")
                .contains("runtimeService.startProcessInstanceByKey(\"rc_twin_process\", key)")
                .contains("pairRegistry.registerAndClassify");
    }

    @Test
    void twoSignalsThatSlugToTheSameNameGetDisambiguatedQueueNames() {
        // "Order Ready" and "OrderReady" both kebab-case to "order-ready" - must not collide.
        List<TargetPlatformMessagingGenerator.GeneratedSource> sources = generator.generate("proj.abc123",
                Set.of("Order Ready", "OrderReady"), Set.of("Order Ready", "OrderReady"), "rc_proxy_process",
                "rc_twin_process");
        String config = find(sources, "RabbitMqConfig").source();

        assertThat(config).contains("proj.abc123.sync.order-ready").contains("proj.abc123.sync.order-ready-2");
    }
}
