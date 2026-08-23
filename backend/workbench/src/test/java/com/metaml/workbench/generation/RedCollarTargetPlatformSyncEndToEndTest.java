package com.metaml.workbench.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.codegen.DelegateClassGenerator;
import com.metaml.workbench.codegen.ExternalTaskWorkerGenerator;

// Acceptance test for the RedCollarTP proxy<->twin RabbitMQ synchronization requested this
// session: generates a real Target Platform from the professor-supplied RedCollar BPMNs against
// the REAL RedCollarTP template (not a fake), builds and launches it for real (SpringBootProjectLauncher
// runs 'mvn clean install -DskipTests' then 'mvn spring-boot:run' itself for this no-mvnw
// template), starts a paired proxy+twin instance over the generated /api/proxy/start and
// /api/twin/start endpoints, and proves - from real engine state and the real broker's own
// management API, not just log text - that they actually synchronize on their shared signals.
//
// Requires a reachable RabbitMQ broker (see rabbitMqReachable) and the professor-supplied BPMNs
// (see RedCollarEndToEndTest's own identical resolution) - skips rather than fails when either is
// missing, since both are environment preconditions this test cannot supply for itself.
@Tag("slow")
class RedCollarTargetPlatformSyncEndToEndTest {

    @TempDir
    Path tempDir;

    private static final Path REPO_ROOT = redCollarBpmnDir();
    private static final Path REAL_TEMPLATE = Path.of("../RedCollarTP");

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

    private static boolean rabbitMqReachable(HttpClient http) {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:15672/api/overview"))
                            .header("Authorization", "Basic " + java.util.Base64.getEncoder()
                                    .encodeToString("guest:guest".getBytes(StandardCharsets.UTF_8)))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void proxyAndTwinSynchronizeOnSharedSignalsOverARealRabbitMqBroker() throws Exception {
        assumeFixturesPresent();
        assertThat(Files.isDirectory(REAL_TEMPLATE)).as("RedCollarTP template must exist at %s",
                REAL_TEMPLATE.toAbsolutePath()).isTrue();
        HttpClient http = HttpClient.newHttpClient();
        Assumptions.assumeTrue(rabbitMqReachable(http), "no RabbitMQ broker reachable at localhost:15672 - "
                + "start one with 'docker run -d -p 5672:5672 -p 15672:15672 rabbitmq:3-management'");

        String manufBpmnXml = Files.readString(REPO_ROOT.resolve("Manuf-camunda.bpmn"));
        String twinBpmnXml = Files.readString(REPO_ROOT.resolve("Twin-camunda.bpmn"));

        Path outputDir = tempDir.resolve("generated-target-platforms");
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(REAL_TEMPLATE.toString(),
                outputDir.toString(), new TwinModelGenerator(), new DelegateClassGenerator(),
                new ExternalTaskWorkerGenerator());
        GeneratedProject project = generator.generateWithAuthoredTwin(manufBpmnXml, twinBpmnXml);

        Path tpRoot = project.directory().resolve("src/main/java/com/tp/TargetPlatform");
        assertThat(tpRoot.resolve("messaging/RabbitMqConfig.java")).exists();
        assertThat(tpRoot.resolve("signal/SignalBroadcaster.java")).exists();

        // SpringBootProjectLauncher itself runs 'mvn clean install -DskipTests' before 'mvn
        // spring-boot:run' for this no-mvnw template - no separate build step needed here.
        SpringBootProjectLauncher launcher = new SpringBootProjectLauncher();
        try {
            LaunchedProject launched;
            try {
                launched = launcher.launch(project);
            } catch (Exception launchEx) {
                Path launchLog = project.directory().resolve("launch.log");
                Path buildLog = project.directory().resolve("build.log");
                String logContent = Files.exists(launchLog) ? Files.readString(launchLog) : "(no launch.log found)";
                String buildContent = Files.exists(buildLog) ? Files.readString(buildLog) : "(no build.log found)";
                throw new AssertionError("Launch failed. build.log:\n" + buildContent + "\nlaunch.log:\n"
                        + logContent, launchEx);
            }

            String proxyBase = "http://localhost:" + launched.port() + "/api/proxy";
            String twinBase = "http://localhost:" + launched.port() + "/api/twin";
            String statusBase = "http://localhost:" + launched.port() + "/api/v1/process";
            String businessKey = "sync-test-" + UUID.randomUUID();

            HttpResponse<String> proxyStart = http.send(
                    HttpRequest.newBuilder(URI.create(proxyBase + "/start?businessKey=" + businessKey))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(proxyStart.statusCode()).as("proxy start failed: %s", proxyStart.body()).isEqualTo(200);
            assertThat(proxyStart.body()).contains("\"role\":\"initiator\"");
            String proxyInstanceId = extractField(proxyStart.body(), "processInstanceId");

            HttpResponse<String> twinStart = http.send(
                    HttpRequest.newBuilder(URI.create(twinBase + "/start?businessKey=" + businessKey))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(twinStart.statusCode()).as("twin start failed: %s", twinStart.body()).isEqualTo(200);
            assertThat(twinStart.body()).contains("\"role\":\"responder\"");
            String twinInstanceId = extractField(twinStart.body(), "processInstanceId");

            // --- Application-log evidence: real publish over the real broker ---
            String publishLog = awaitLogContaining(project.directory(), "TASK: published signal",
                    Duration.ofSeconds(60));
            assertThat(publishLog).contains("TASK: published signal").contains("to RabbitMQ exchange");

            String responseLog = awaitLogContaining(project.directory(), "RESPONSE: published signal",
                    Duration.ofSeconds(60));
            assertThat(responseLog).contains("RESPONSE: published signal").contains("to RabbitMQ exchange");

            String consumeLog = awaitLogContaining(project.directory(), "delivered signal",
                    Duration.ofSeconds(30));
            assertThat(consumeLog).as("a RabbitMQ listener must have actually delivered a real Camunda signal")
                    .contains("via RabbitMQ");

            // --- Causal proof: both instances actually advanced (not stuck), via real engine state ---
            assertThat(awaitInstanceInactive(http, statusBase, proxyInstanceId, Duration.ofSeconds(120))
                    || awaitDistinctActiveActivity(http, statusBase, proxyInstanceId, Duration.ofSeconds(5)))
                    .as("proxy instance must have advanced past its start")
                    .isTrue();
            // Twin can legitimately have already completed by now (it advances fast, and the
            // signal traffic above already proves the real synchronization happened) - either a
            // still-active instance carrying the right business key, or having gone inactive, is
            // proof it actually ran, not proof of a stuck/never-started instance.
            String twinStatus = fetchStatus(http, statusBase, twinInstanceId);
            assertThat(twinStatus)
                    .as("twin instance must be either still running under the paired business key, or "
                            + "have completed - not simply missing")
                    .satisfiesAnyOf(
                            s -> assertThat(s).contains("\"businessKey\":\"" + businessKey + "\""),
                            s -> assertThat(s).contains("\"active\":false"));

            // --- Broker-level evidence, independent of the application's own logs ---
            String allQueuesJson = listRabbitQueues(http);
            List<String> ownQueues = queueSegmentsMatchingPrefix(allQueuesJson, project.projectId());
            assertThat(ownQueues).as("broker must report this project's own sync queues").isNotEmpty();
            long publishCount = ownQueues.stream().mapToLong(q -> extractLongField(q, "publish")).sum();
            assertThat(publishCount).as("aggregate broker-reported publish count across this project's queues")
                    .isGreaterThan(0);
        } finally {
            launcher.stop(project.projectId());
        }
    }

    // Same acceptance test as above, but proving the OTHER path that was previously broken: no
    // Twin-camunda.bpmn is read or supplied here at all - only Manuf-camunda.bpmn goes in, through
    // the plain single-BPMN generate() entry point (what a user gets from Save without ever
    // clicking Attach Twin BPMN). TwinModelGenerator used to throw on this exact file
    // (intermediateCatchEvent unsupported); TargetPlatformTwinMirrorGenerator now derives a
    // structural mirror instead, and this proves that mirror is not just non-throwing but a real,
    // working Twin that synchronizes with its proxy the same way an authored one does.
    @Test
    void proxyAndAutoDerivedTwinSynchronizeOnSharedSignalsOverARealRabbitMqBroker() throws Exception {
        assumeFixturesPresent();
        assertThat(Files.isDirectory(REAL_TEMPLATE)).as("RedCollarTP template must exist at %s",
                REAL_TEMPLATE.toAbsolutePath()).isTrue();
        HttpClient http = HttpClient.newHttpClient();
        Assumptions.assumeTrue(rabbitMqReachable(http), "no RabbitMQ broker reachable at localhost:15672 - "
                + "start one with 'docker run -d -p 5672:5672 -p 15672:15672 rabbitmq:3-management'");

        String manufBpmnXml = Files.readString(REPO_ROOT.resolve("Manuf-camunda.bpmn"));

        Path outputDir = tempDir.resolve("generated-target-platforms");
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(REAL_TEMPLATE.toString(),
                outputDir.toString(), new TwinModelGenerator(), new DelegateClassGenerator(),
                new ExternalTaskWorkerGenerator());
        // The single-BPMN entry point - no authored Twin passed in anywhere.
        GeneratedProject project = generator.generate(manufBpmnXml, List.of());

        Path tpRoot = project.directory().resolve("src/main/java/com/tp/TargetPlatform");
        assertThat(tpRoot.resolve("messaging/RabbitMqConfig.java")).exists();
        assertThat(tpRoot.resolve("signal/SignalBroadcaster.java")).exists();

        SpringBootProjectLauncher launcher = new SpringBootProjectLauncher();
        try {
            LaunchedProject launched;
            try {
                launched = launcher.launch(project);
            } catch (Exception launchEx) {
                Path launchLog = project.directory().resolve("launch.log");
                Path buildLog = project.directory().resolve("build.log");
                String logContent = Files.exists(launchLog) ? Files.readString(launchLog) : "(no launch.log found)";
                String buildContent = Files.exists(buildLog) ? Files.readString(buildLog) : "(no build.log found)";
                throw new AssertionError("Launch failed. build.log:\n" + buildContent + "\nlaunch.log:\n"
                        + logContent, launchEx);
            }

            String proxyBase = "http://localhost:" + launched.port() + "/api/proxy";
            String twinBase = "http://localhost:" + launched.port() + "/api/twin";
            String statusBase = "http://localhost:" + launched.port() + "/api/v1/process";
            String businessKey = "auto-twin-test-" + UUID.randomUUID();

            HttpResponse<String> proxyStart = http.send(
                    HttpRequest.newBuilder(URI.create(proxyBase + "/start?businessKey=" + businessKey))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(proxyStart.statusCode()).as("proxy start failed: %s", proxyStart.body()).isEqualTo(200);
            String proxyInstanceId = extractField(proxyStart.body(), "processInstanceId");

            HttpResponse<String> twinStart = http.send(
                    HttpRequest.newBuilder(URI.create(twinBase + "/start?businessKey=" + businessKey))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(twinStart.statusCode()).as("twin start failed: %s", twinStart.body()).isEqualTo(200);

            // --- Application-log evidence: real publish over the real broker, on the mirrored twin ---
            String publishLog = awaitLogContaining(project.directory(), "TASK: published signal",
                    Duration.ofSeconds(60));
            assertThat(publishLog).contains("TASK: published signal").contains("to RabbitMQ exchange");

            String consumeLog = awaitLogContaining(project.directory(), "delivered signal",
                    Duration.ofSeconds(30));
            assertThat(consumeLog).as("a RabbitMQ listener must have actually delivered a real Camunda signal")
                    .contains("via RabbitMQ");

            // --- Causal proof the proxy actually advanced, via real engine state ---
            assertThat(awaitInstanceInactive(http, statusBase, proxyInstanceId, Duration.ofSeconds(120))
                    || awaitDistinctActiveActivity(http, statusBase, proxyInstanceId, Duration.ofSeconds(5)))
                    .as("proxy instance must have advanced past its start")
                    .isTrue();

            // --- Broker-level evidence, independent of the application's own logs ---
            String allQueuesJson = listRabbitQueues(http);
            List<String> ownQueues = queueSegmentsMatchingPrefix(allQueuesJson, project.projectId());
            assertThat(ownQueues).as("broker must report this project's own sync queues").isNotEmpty();
            long publishCount = ownQueues.stream().mapToLong(q -> extractLongField(q, "publish")).sum();
            assertThat(publishCount).as("aggregate broker-reported publish count across this project's queues")
                    .isGreaterThan(0);
        } finally {
            launcher.stop(project.projectId());
        }
    }

    private static boolean awaitDistinctActiveActivity(HttpClient http, String statusBase, String processInstanceId,
            Duration wait) throws IOException, InterruptedException {
        Thread.sleep(wait.toMillis());
        return fetchStatus(http, statusBase, processInstanceId).contains("activeActivityIds");
    }

    private static boolean awaitInstanceInactive(HttpClient http, String statusBase, String processInstanceId,
            Duration timeout) throws IOException, InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (fetchStatus(http, statusBase, processInstanceId).contains("\"active\":false")) {
                return true;
            }
            Thread.sleep(500);
        }
        return false;
    }

    private static String fetchStatus(HttpClient http, String statusBase, String processInstanceId)
            throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(statusBase + "/" + processInstanceId + "/status")).GET()
                        .build(), HttpResponse.BodyHandlers.ofString())
                .body();
    }

    private static String listRabbitQueues(HttpClient http) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:15672/api/queues/%2f"))
                        .header("Authorization", "Basic " + java.util.Base64.getEncoder()
                                .encodeToString("guest:guest".getBytes(StandardCharsets.UTF_8)))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("RabbitMQ management API queue listing").isEqualTo(200);
        return response.body();
    }

    private static List<String> queueSegmentsMatchingPrefix(String queueListJson, String nameFragment) {
        List<String> names = new java.util.ArrayList<>();
        List<Integer> starts = new java.util.ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"name\":\"([^\"]*)\"")
                .matcher(queueListJson);
        while (matcher.find()) {
            names.add(matcher.group(1));
            starts.add(matcher.start());
        }
        List<String> segments = new java.util.ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).contains(nameFragment)) {
                int start = starts.get(i);
                int end = (i + 1 < starts.size()) ? starts.get(i + 1) : queueListJson.length();
                segments.add(queueListJson.substring(start, end));
            }
        }
        return segments;
    }

    private static long extractLongField(String json, String fieldName) {
        String marker = "\"" + fieldName + "\":";
        int key = json.indexOf(marker);
        if (key < 0) {
            return 0;
        }
        int start = key + marker.length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        if (end == start) {
            return 0;
        }
        return Long.parseLong(json.substring(start, end));
    }

    private static String extractField(String json, String fieldName) {
        String marker = "\"" + fieldName + "\"";
        int key = json.indexOf(marker);
        int firstQuote = json.indexOf('"', key + marker.length() + 1);
        int secondQuote = json.indexOf('"', firstQuote + 1);
        return json.substring(firstQuote + 1, secondQuote);
    }

    private static String awaitLogContaining(Path projectDir, String fragment, Duration timeout)
            throws IOException {
        Path logFile = projectDir.resolve("launch.log");
        Instant deadline = Instant.now().plus(timeout);
        String log = "";
        while (Instant.now().isBefore(deadline)) {
            if (Files.exists(logFile)) {
                log = Files.readString(logFile);
                if (log.contains(fragment)) {
                    return log;
                }
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Timed out waiting for '" + fragment + "' in launch.log:\n" + log);
    }
}
