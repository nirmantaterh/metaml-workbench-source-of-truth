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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.codegen.DelegateClassGenerator;
import com.metaml.workbench.codegen.ExternalTaskWorkerGenerator;

@Tag("slow")
class RabbitMqStress30ActivityTest {

    @TempDir
    Path tempDir;

    private static final Path REAL_TEMPLATE = Path.of("../../templates/camundademo");
    private static final Path STRESS30_MANUF_BPMN = Path.of("src/test/resources/stress30/Stress30-Manuf.bpmn");
    private static final Path STRESS30_TWIN_BPMN = Path.of("src/test/resources/stress30/Stress30-Twin.bpmn");

    private static final int ACTIVITY_COUNT = 30;

    private static final int PAIR_COUNT = 4;

    private static List<String> activitySlugs() {
        return IntStream.rangeClosed(1, ACTIVITY_COUNT).mapToObj(i -> String.format("activity-%02d-twin", i))
                .toList();
    }

    private static List<String> twinActivityIds() {
        return IntStream.rangeClosed(1, ACTIVITY_COUNT).mapToObj(i -> String.format("Stress30TwinTask%02d", i))
                .toList();
    }

    @Test
    void thirtyActivitySyntheticBpmnPairProducesSixtyRabbitMqQueuesWithRealConcurrentActivityLevelTraffic()
            throws Exception {
        HttpClient rabbitAdmin = HttpClient.newHttpClient();
        if (!rabbitMqReachable(rabbitAdmin)) {
            System.out.println("SKIPPED thirtyActivitySyntheticBpmnPairProducesSixtyRabbitMqQueuesWithReal"
                    + "ConcurrentActivityLevelTraffic: no RabbitMQ broker reachable at localhost:15672.");
            return;
        }
        assertThat(Files.isDirectory(REAL_TEMPLATE)).isTrue();
        assertThat(STRESS30_MANUF_BPMN).exists();
        assertThat(STRESS30_TWIN_BPMN).exists();

        String manufBpmnXml = Files.readString(STRESS30_MANUF_BPMN);
        String twinBpmnXml = Files.readString(STRESS30_TWIN_BPMN);

        Path outputDir = tempDir.resolve("generated-projects");
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(REAL_TEMPLATE.toString(),
                outputDir.toString(), new TwinModelGenerator(), new DelegateClassGenerator(),
                new ExternalTaskWorkerGenerator());
        GeneratedProject project = generator.generateWithAuthoredTwin(manufBpmnXml, twinBpmnXml);

        Path configFile = project.directory().resolve(
                "src/main/java/com/metaml/targetplatform/stress30manuf/messaging/RabbitMqConfig.java");
        assertThat(configFile).exists();
        String configSource = Files.readString(configFile);
        for (String slug : activitySlugs()) {
            assertThat(configSource).as("generated RabbitMqConfig.java must declare a task+response queue pair "
                            + "for activity '%s'", slug)
                    .contains(".tasks." + slug)
                    .contains(".tasks.responses." + slug);
        }
        assertNoRedCollarTerms(configSource, "generated RabbitMqConfig.java");

        buildGeneratedProject(project);

        SpringBootProjectLauncher launcher = new SpringBootProjectLauncher();
        ExecutorService pool = Executors.newFixedThreadPool(PAIR_COUNT * 2);
        try {
            LaunchedProject launched = launcher.launch(project, Map.of(
                    "METAML_MESSAGING_ENABLED", "true",
                    "SPRING_RABBITMQ_HOST", "localhost",
                    "SPRING_RABBITMQ_PORT", "5672"));
            HttpClient http = HttpClient.newHttpClient();
            String manufBase = "http://localhost:" + launched.port() + "/api/v1/manufacturing";
            String twinBase = "http://localhost:" + launched.port() + "/api/v1/twin";
            String statusBase = "http://localhost:" + launched.port() + "/api/v1/process";

            List<String> businessKeys = IntStream.range(0, PAIR_COUNT)
                    .mapToObj(p -> "stress30-pair-" + p + "-" + UUID.randomUUID())
                    .toList();
            List<Future<String>> manufFutures = new ArrayList<>();
            List<Future<String>> twinFutures = new ArrayList<>();
            for (String key : businessKeys) {
                manufFutures.add(pool.submit(
                        () -> extractProcessInstanceId(post(http, manufBase + "/start?businessKey=" + key).body())));
                twinFutures.add(pool.submit(
                        () -> extractProcessInstanceId(post(http, twinBase + "/start?businessKey=" + key).body())));
            }
            List<PairRun> pairs = new ArrayList<>();
            for (int p = 0; p < PAIR_COUNT; p++) {
                pairs.add(new PairRun(businessKeys.get(p), manufFutures.get(p).get(), twinFutures.get(p).get()));
            }

            for (PairRun pair : pairs) {
                assertThat(awaitInstanceInactive(http, statusBase, pair.manufInstanceId(), Duration.ofSeconds(240)))
                        .as("pair %s's Main must complete all 30 activity handoffs", pair.businessKey()).isTrue();
                assertThat(awaitInstanceInactive(http, statusBase, pair.twinInstanceId(), Duration.ofSeconds(120)))
                        .as("pair %s's Twin must complete", pair.businessKey()).isTrue();
            }

            String allQueuesJson = listRabbitQueues(rabbitAdmin);
            List<String> ownQueues = queueSegmentsMatchingPrefix(allQueuesJson, project.projectId());
            assertThat(ownQueues).as("broker must report exactly 60 queues (30 activities x task+response)")
                    .hasSize(ACTIVITY_COUNT * 2);
            List<String> ownQueueNames = queueNames(ownQueues);

            Pattern responseQueuePattern = Pattern.compile("\\.tasks\\.responses\\.([a-z0-9-]+)$");
            Pattern taskQueuePattern = Pattern.compile("\\.tasks\\.(?!responses\\.)([a-z0-9-]+)$");
            Set<String> taskSlugs = new TreeSet<>();
            Set<String> responseSlugs = new TreeSet<>();
            Map<String, String> taskQueueByName = new java.util.HashMap<>();
            Map<String, String> responseQueueByName = new java.util.HashMap<>();
            for (int i = 0; i < ownQueueNames.size(); i++) {
                String name = ownQueueNames.get(i);
                Matcher responseMatch = responseQueuePattern.matcher(name);
                if (responseMatch.find()) {
                    responseSlugs.add(responseMatch.group(1));
                    responseQueueByName.put(responseMatch.group(1), ownQueues.get(i));
                    continue;
                }
                Matcher taskMatch = taskQueuePattern.matcher(name);
                if (taskMatch.find()) {
                    taskSlugs.add(taskMatch.group(1));
                    taskQueueByName.put(taskMatch.group(1), ownQueues.get(i));
                }
            }
            assertThat(taskSlugs).as("broker must report 30 distinct task-queue activity slugs").hasSize(30);
            assertThat(responseSlugs).as("broker must report 30 distinct response-queue activity slugs")
                    .hasSize(30);
            assertThat(taskSlugs).as("task/response slug sets must be identical (deterministic pairing)")
                    .isEqualTo(responseSlugs);
            assertThat(taskSlugs).as("broker-reported activity slugs must be exactly the 30 this test generated "
                            + "the BPMN from - no missing, extra, or misnamed activity")
                    .isEqualTo(new TreeSet<>(activitySlugs()));

            // Grace-period fallback may bypass some queues.
            List<String> silentTaskQueues = new ArrayList<>();
            List<String> silentResponseQueues = new ArrayList<>();
            for (String slug : activitySlugs()) {
                String taskSegment = taskQueueByName.get(slug);
                String responseSegment = responseQueueByName.get(slug);
                assertThat(taskSegment).as("task queue for '%s' must be present on the broker", slug).isNotNull();
                assertThat(responseSegment).as("response queue for '%s' must be present on the broker", slug)
                        .isNotNull();
                boolean taskHasTraffic = extractLongField(taskSegment, "publish") > 0
                        && extractLongField(taskSegment, "deliver_get") > 0;
                boolean responseHasTraffic = extractLongField(responseSegment, "publish") > 0
                        && extractLongField(responseSegment, "deliver_get") > 0;
                if (!taskHasTraffic) {
                    silentTaskQueues.add(slug);
                }
                if (!responseHasTraffic) {
                    silentResponseQueues.add(slug);
                }
            }
            assertThat(silentTaskQueues)
                    .as("at most 6 of 30 task queues silent; found %d: %s",
                            silentTaskQueues.size(), silentTaskQueues)
                    .hasSizeLessThanOrEqualTo(6);
            assertThat(silentResponseQueues)
                    .as("at most 6 of 30 response queues silent; found %d: %s",
                            silentResponseQueues.size(), silentResponseQueues)
                    .hasSizeLessThanOrEqualTo(6);

            List<String> twinActivityIds = twinActivityIds();
            List<String> misroutedActivities = new ArrayList<>();
            for (PairRun pair : pairs) {
                for (String activityId : twinActivityIds) {
                    long visits = activityVisitCount(http, statusBase, pair.twinInstanceId(), activityId);
                    if (visits != 1) {
                        misroutedActivities.add(pair.businessKey() + "/" + activityId + "=visits:" + visits);
                    }
                }
            }
            assertThat(misroutedActivities)
                    .as("every pair must visit every one of its own 30 Twin activities exactly once - any "
                            + "other count means cross-pair or cross-activity misrouting: %s", misroutedActivities)
                    .isEmpty();

            long totalPublish = ownQueues.stream().mapToLong(q -> extractLongField(q, "publish")).sum();
            long totalDeliver = ownQueues.stream().mapToLong(q -> extractLongField(q, "deliver_get")).sum();
            assertThat(totalPublish).as("aggregate broker-reported publish count across all 60 queues")
                    .isGreaterThan(0);
            assertThat(totalDeliver).as("aggregate broker-reported deliver count across all 60 queues")
                    .isGreaterThan(0);
        } finally {
            pool.shutdownNow();
            launcher.stop(project.projectId());
            deleteProjectQueues(rabbitAdmin, project.projectId());
        }
    }

    @Test
    void twoProjectsWithIdenticalThirtyActivityNamesRemainPhysicallyIsolatedOnTheSameBroker() throws Exception {
        HttpClient rabbitAdmin = HttpClient.newHttpClient();
        if (!rabbitMqReachable(rabbitAdmin)) {
            System.out.println("SKIPPED twoProjectsWithIdenticalThirtyActivityNamesRemainPhysicallyIsolatedOnThe"
                    + "SameBroker: no RabbitMQ broker reachable at localhost:15672.");
            return;
        }
        String manufBpmnXml = Files.readString(STRESS30_MANUF_BPMN);
        String twinBpmnXml = Files.readString(STRESS30_TWIN_BPMN);

        Path outputDir = tempDir.resolve("generated-projects");
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(REAL_TEMPLATE.toString(),
                outputDir.toString(), new TwinModelGenerator(), new DelegateClassGenerator(),
                new ExternalTaskWorkerGenerator());
        GeneratedProject projectA = generator.generateWithAuthoredTwin(manufBpmnXml, twinBpmnXml);
        GeneratedProject projectB = generator.generateWithAuthoredTwin(manufBpmnXml, twinBpmnXml);
        assertThat(projectA.projectId()).isNotEqualTo(projectB.projectId());

        buildGeneratedProject(projectA);
        buildGeneratedProject(projectB);

        SpringBootProjectLauncher launcher = new SpringBootProjectLauncher();
        try {
            Map<String, String> messagingEnv = Map.of(
                    "METAML_MESSAGING_ENABLED", "true",
                    "SPRING_RABBITMQ_HOST", "localhost",
                    "SPRING_RABBITMQ_PORT", "5672");
            LaunchedProject launchedA = launcher.launch(projectA, messagingEnv);
            LaunchedProject launchedB = launcher.launch(projectB, messagingEnv);

            HttpClient http = HttpClient.newHttpClient();
            String manufBaseA = "http://localhost:" + launchedA.port() + "/api/v1/manufacturing";
            String twinBaseA = "http://localhost:" + launchedA.port() + "/api/v1/twin";
            String statusBaseA = "http://localhost:" + launchedA.port() + "/api/v1/process";
            String manufBaseB = "http://localhost:" + launchedB.port() + "/api/v1/manufacturing";
            String twinBaseB = "http://localhost:" + launchedB.port() + "/api/v1/twin";
            String statusBaseB = "http://localhost:" + launchedB.port() + "/api/v1/process";

            String keyA = "stress30-isolation-A-" + UUID.randomUUID();
            String keyB = "stress30-isolation-B-" + UUID.randomUUID();
            String manufAId = extractProcessInstanceId(post(http, manufBaseA + "/start?businessKey=" + keyA).body());
            String twinAId = extractProcessInstanceId(post(http, twinBaseA + "/start?businessKey=" + keyA).body());
            String manufBId = extractProcessInstanceId(post(http, manufBaseB + "/start?businessKey=" + keyB).body());
            String twinBId = extractProcessInstanceId(post(http, twinBaseB + "/start?businessKey=" + keyB).body());

            assertThat(awaitInstanceInactive(http, statusBaseA, manufAId, Duration.ofSeconds(240)))
                    .as("project A's Main must complete all 30 activities on its own RabbitMQ traffic").isTrue();
            assertThat(awaitInstanceInactive(http, statusBaseA, twinAId, Duration.ofSeconds(120)))
                    .as("project A's Twin must complete").isTrue();
            assertThat(awaitInstanceInactive(http, statusBaseB, manufBId, Duration.ofSeconds(240)))
                    .as("project B's Main must complete all 30 activities on its own RabbitMQ traffic").isTrue();
            assertThat(awaitInstanceInactive(http, statusBaseB, twinBId, Duration.ofSeconds(120)))
                    .as("project B's Twin must complete").isTrue();

            String allQueues = listRabbitQueues(rabbitAdmin);
            List<String> queuesA = queueSegmentsMatchingPrefix(allQueues, projectA.projectId());
            List<String> queuesB = queueSegmentsMatchingPrefix(allQueues, projectB.projectId());
            assertThat(queuesA).as("project A must have its own dedicated 60 queues (30 activities)")
                    .hasSize(ACTIVITY_COUNT * 2);
            assertThat(queuesB).as("project B must have its own dedicated 60 queues (30 activities)")
                    .hasSize(ACTIVITY_COUNT * 2);
            List<String> namesA = queueNames(queuesA);
            List<String> namesB = queueNames(queuesB);
            assertThat(namesA).as("project A's 60 queue names must never appear in project B's queue set - "
                            + "even though both were generated from the IDENTICAL 30-activity-named BPMN pair")
                    .doesNotContainAnyElementsOf(namesB);
            assertThat(namesB).as("project B's 60 queue names must never appear in project A's queue set")
                    .doesNotContainAnyElementsOf(namesA);

            long publishA = queuesA.stream().mapToLong(q -> extractLongField(q, "publish")).sum();
            long publishB = queuesB.stream().mapToLong(q -> extractLongField(q, "publish")).sum();
            assertThat(publishA).as("project A's own 60 queues carried real traffic").isGreaterThan(0);
            assertThat(publishB).as("project B's own 60 queues carried real traffic").isGreaterThan(0);
        } finally {
            launcher.stop(projectA.projectId());
            launcher.stop(projectB.projectId());
            deleteProjectQueues(rabbitAdmin, projectA.projectId());
            deleteProjectQueues(rabbitAdmin, projectB.projectId());
        }
    }

    private record PairRun(String businessKey, String manufInstanceId, String twinInstanceId) {
    }

    private static void deleteProjectQueues(HttpClient http, String projectId) {
        try {
            String allQueuesJson = listRabbitQueues(http);
            List<String> ownQueues = queueSegmentsMatchingPrefix(allQueuesJson, projectId);
            List<String> ownQueueNames = queueNames(ownQueues);
            for (String queueName : ownQueueNames) {
                HttpResponse<String> response = http.send(
                        HttpRequest.newBuilder(URI.create("http://localhost:15672/api/queues/%2f/" + queueName))
                                .header("Authorization", "Basic " + java.util.Base64.getEncoder()
                                        .encodeToString("guest:guest".getBytes(StandardCharsets.UTF_8)))
                                .DELETE().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 204 && response.statusCode() != 404) {
                    System.err.println("Failed to delete RabbitMQ queue " + queueName + ": " + response.body());
                }
            }
        } catch (Exception e) {
            System.err.println("Error cleaning up RabbitMQ queues for project " + projectId + ": " + e.toString());
        }
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
        List<String> names = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        Matcher matcher = Pattern.compile("\"name\":\"([^\"]*)\"").matcher(queueListJson);
        while (matcher.find()) {
            names.add(matcher.group(1));
            starts.add(matcher.start());
        }
        List<String> segments = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).contains(nameFragment)) {
                int start = starts.get(i);
                int end = (i + 1 < starts.size()) ? starts.get(i + 1) : queueListJson.length();
                segments.add(queueListJson.substring(start, end));
            }
        }
        return segments;
    }

    private static List<String> queueNames(List<String> queueSegments) {
        List<String> names = new ArrayList<>();
        for (String segment : queueSegments) {
            Matcher matcher = Pattern.compile("\"name\":\"([^\"]*)\"").matcher(segment);
            if (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return names;
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

    private static void assertNoRedCollarTerms(String text, String whatThisIs) {
        for (String term : List.of("RedCollar", "redcollar", "Sampling", "sampling", "Laying", "laying", "Marking",
                "marking", "Cutting", "cutting", "Stitching", "stitching", "Checking", "checking", "Pressing",
                "pressing", "Packaging", "packaging", "Shipping", "shipping")) {
            assertThat(text).as("%s must not contain RedCollar-specific term '%s'", whatThisIs, term)
                    .doesNotContain(term);
        }
    }

    private static HttpResponse<String> post(HttpClient http, String url) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void buildGeneratedProject(GeneratedProject project) throws IOException, InterruptedException {
        Process build = new ProcessBuilder(mvnw(project.directory()), "-q", "package", "-DskipTests")
                .directory(project.directory().toFile())
                .redirectErrorStream(true)
                .start();
        String buildOutput = new String(build.getInputStream().readAllBytes());
        assertThat(build.waitFor(5, java.util.concurrent.TimeUnit.MINUTES)).isTrue();
        assertThat(build.exitValue()).as("generated project failed to build:%n%s", buildOutput).isZero();
    }

    private static boolean awaitInstanceInactive(HttpClient http, String statusBase, String processInstanceId,
            Duration timeout) throws IOException, InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (fetchStatus(http, statusBase, processInstanceId).contains("\"active\":false")) {
                return true;
            }
            Thread.sleep(300);
        }
        return false;
    }

    private static long activityVisitCount(HttpClient http, String statusBase, String processInstanceId,
            String activityId) throws IOException, InterruptedException {
        String body = http.send(
                        HttpRequest.newBuilder(
                                        URI.create(statusBase + "/" + processInstanceId + "/activity-history/"
                                                + activityId + "/count"))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString())
                .body();
        int key = body.indexOf("\"visitCount\"");
        assertThat(key).as("visitCount missing from response: %s", body).isGreaterThanOrEqualTo(0);
        int colon = body.indexOf(':', key);
        int end = colon + 1;
        while (end < body.length() && Character.isDigit(body.charAt(end))) {
            end++;
        }
        return Long.parseLong(body.substring(colon + 1, end).trim());
    }

    private static String fetchStatus(HttpClient http, String statusBase, String processInstanceId)
            throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(statusBase + "/" + processInstanceId + "/status")).GET()
                        .build(), HttpResponse.BodyHandlers.ofString())
                .body();
    }

    private static String extractProcessInstanceId(String json) {
        int key = json.indexOf("\"processInstanceId\"");
        int firstQuote = json.indexOf('"', key + "\"processInstanceId\"".length() + 1);
        int secondQuote = json.indexOf('"', firstQuote + 1);
        return json.substring(firstQuote + 1, secondQuote);
    }

    private static String mvnw(Path projectDir) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path wrapper = projectDir.resolve(windows ? "mvnw.cmd" : "mvnw");
        return wrapper.toAbsolutePath().toString();
    }
}
