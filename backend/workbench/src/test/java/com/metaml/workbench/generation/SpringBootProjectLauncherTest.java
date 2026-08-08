package com.metaml.workbench.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Doesn't spin up a real Spring Boot + Camunda app - that would make every run of this suite take
// the better part of a minute per test. Instead stands in a fake "mvnw.cmd" that just opens a raw
// TCP listener on the port SpringBootProjectLauncher hands it via the SERVER_PORT environment
// variable and sits there, which is enough to exercise everything this class is actually
// responsible for: picking a free port, waiting for something to start listening on it, tracking
// it, and tearing the whole process tree down again on stop(). The real "does the generated app
// itself come up and serve traffic" question is a template/generator concern, already covered by
// the real mvn compile verification done against templates/camundademo elsewhere.
class SpringBootProjectLauncherTest {

    // PowerShell TCP listener standing in for a real Spring Boot app - reads the same SERVER_PORT
    // the launcher sets for the real command, so this fake never has to know anything about Maven's
    // own argument parsing
    private static final String FAKE_LISTENER_SCRIPT = """
            @echo off
            powershell -NoProfile -Command "$l = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, [int]$env:SERVER_PORT); $l.Start(); Start-Sleep -Seconds 300; $l.Stop()"
            """;

    @TempDir
    Path projectDir;

    private final SpringBootProjectLauncher launcher = new SpringBootProjectLauncher();

    private GeneratedProject fakeProject(String projectId) throws IOException {
        Files.writeString(projectDir.resolve("mvnw.cmd"), FAKE_LISTENER_SCRIPT, StandardCharsets.UTF_8);
        return new GeneratedProject(projectId, projectDir, "fakeProcess");
    }

    @Test
    void launchWaitsForTheAppToActuallyStartListeningBeforeReturning() throws IOException {
        LaunchedProject launched = launcher.launch(fakeProject("p1"));

        assertThat(launched.port()).isGreaterThan(0);
        assertThat(isListening(launched.port())).isTrue();
        assertThat(launcher.find("p1")).contains(launched);

        launcher.stop("p1");
    }

    @Test
    void twoDifferentProjectsGetTwoDifferentAutoAssignedPorts() throws IOException {
        LaunchedProject first = launcher.launch(fakeProject("p1"));

        Path secondDir = Files.createTempDirectory("second-project");
        Files.writeString(secondDir.resolve("mvnw.cmd"), FAKE_LISTENER_SCRIPT, StandardCharsets.UTF_8);
        LaunchedProject second = launcher.launch(new GeneratedProject("p2", secondDir, "fakeProcess2"));

        assertThat(first.port()).isNotEqualTo(second.port());
        assertThat(launcher.listRunning()).extracting(LaunchedProject::projectId).containsExactlyInAnyOrder("p1",
                "p2");

        launcher.stop("p1");
        launcher.stop("p2");
    }

    @Test
    void stopActuallyFreesThePortNotJustForgetsAboutIt() throws IOException {
        LaunchedProject launched = launcher.launch(fakeProject("p1"));
        assertThat(isListening(launched.port())).isTrue();

        boolean stopped = launcher.stop("p1");

        assertThat(stopped).isTrue();
        // give the OS a moment to actually release the socket after the process tree is killed
        assertStopsListening(launched.port());
        assertThat(launcher.find("p1")).isEmpty();
    }

    @Test
    void stoppingAProjectThatWasNeverLaunchedIsANoOpNotAnError() {
        assertThat(launcher.stop("never-launched")).isFalse();
    }

    @Test
    void relaunchingTheSameProjectIdStopsTheOldRunBeforeStartingTheNewOne() throws IOException {
        GeneratedProject project = fakeProject("p1");
        LaunchedProject first = launcher.launch(project);

        LaunchedProject second = launcher.launch(project);

        assertThat(launcher.listRunning()).extracting(LaunchedProject::projectId).containsExactly("p1");
        assertStopsListening(first.port());
        assertThat(isListening(second.port())).isTrue();

        launcher.stop("p1");
    }

    // the real launch() path, not a reimplementation of its polling loop - a 2-second launcher
    // (rather than the production 5-minute one) makes it affordable to actually let this run to
    // its real failure instead of only inspecting the source for a throw statement
    @Test
    void aProjectWhoseAppNeverStartsListeningFailsLoudlyInsteadOfHangingForever() throws IOException {
        Files.writeString(projectDir.resolve("mvnw.cmd"), "@echo off\r\nping -n 300 127.0.0.1 >nul\r\n",
                StandardCharsets.UTF_8);
        SpringBootProjectLauncher shortTimeoutLauncher = new SpringBootProjectLauncher(Duration.ofSeconds(2));

        assertThatThrownBy(() -> shortTimeoutLauncher.launch(new GeneratedProject("p1", projectDir, "fakeProcess")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not start listening");
        // the failed launch shouldn't leave anything behind for a later find()/listRunning() to see
        assertThat(shortTimeoutLauncher.find("p1")).isEmpty();
    }

    // stopActuallyFreesThePortNotJustForgetsAboutIt above proves the port goes quiet, which is not
    // quite the same claim: a listener can go away while the process tree that owned it is still
    // alive. This one holds on to the actual Process the launcher was tracking and checks the OS
    // reaped it and everything it spawned.
    @Test
    void stopLeavesNoLiveProcessOrDescendantBehindNotJustAQuietPort() throws Exception {
        LaunchedProject launched = launcher.launch(fakeProject("p1"));
        Process process = trackedProcess(launcher, "p1");
        assertThat(process.isAlive()).isTrue();

        assertThat(launcher.stop("p1")).isTrue();

        assertStopsListening(launched.port());
        assertThat(process.isAlive()).isFalse();
        assertThat(process.descendants().filter(ProcessHandle::isAlive).count()).isZero();
        assertThat(launcher.listRunning()).isEmpty();
    }

    // the workbench shutting down used to leave every generated app it had started still running,
    // still holding its port, with the next workbench instance having no record of them at all
    @Test
    void shutdownStopsEveryProjectThatWasStillRunning() throws IOException {
        LaunchedProject first = launcher.launch(fakeProject("p1"));

        Path secondDir = Files.createTempDirectory("second-project");
        Files.writeString(secondDir.resolve("mvnw.cmd"), FAKE_LISTENER_SCRIPT, StandardCharsets.UTF_8);
        LaunchedProject second = launcher.launch(new GeneratedProject("p2", secondDir, "fakeProcess2"));

        launcher.stopEverythingStillRunning();

        assertThat(launcher.listRunning()).isEmpty();
        assertStopsListening(first.port());
        assertStopsListening(second.port());
    }

    // H3. Two threads launching the SAME projectId at once used to both get past the stop() at the
    // top of launch(), both spawn a child, and whichever put() landed second silently orphaned the
    // other one's process - unreachable through find()/listRunning(), so unkillable through stop(),
    // with its port held until the workbench itself died. Proven by the negative here, not by
    // eyeballing the lock: after both threads return, exactly one of the two ports they were handed
    // is still listening, and it's the one the launcher is actually tracking.
    @Test
    void twoConcurrentLaunchesOfTheSameProjectLeaveExactlyOneProcessAlive() throws Exception {
        GeneratedProject project = fakeProject("p1");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch bothReady = new CountDownLatch(2);
        try {
            List<Future<LaunchedProject>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    bothReady.countDown();
                    bothReady.await();
                    return launcher.launch(project);
                }));
            }
            LaunchedProject a = results.get(0).get(90, TimeUnit.SECONDS);
            LaunchedProject b = results.get(1).get(90, TimeUnit.SECONDS);

            assertThat(launcher.listRunning()).extracting(LaunchedProject::projectId).containsExactly("p1");
            LaunchedProject survivor = launcher.find("p1").orElseThrow();
            assertThat(survivor.port()).isIn(a.port(), b.port());
            assertThat(isListening(survivor.port())).isTrue();

            if (a.port() != b.port()) {
                // the loser's process must have been stopped by the winner's stop(), not orphaned
                int loserPort = survivor.port() == a.port() ? b.port() : a.port();
                assertStopsListening(loserPort);
            }
            assertThat(trackedProcess(launcher, "p1").isAlive()).isTrue();
        } finally {
            pool.shutdownNow();
            launcher.stop("p1");
        }
    }

    // H4. A generated project that fails to compile - the likeliest failure in a live demo, since
    // the delegates come from whatever the user modelled - exits within seconds, but awaitReady
    // only ever looked at the port, so the caller sat out the entire readiness timeout and then got
    // a message that said nothing about why. The 30s timeout here is the load-bearing part of the
    // test: if the fix regressed, this would take 30s instead of the couple it takes now.
    @Test
    void aProjectWhoseProcessDiesImmediatelyFailsRightAwayInsteadOfWaitingOutTheTimeout() throws IOException {
        Files.writeString(projectDir.resolve("mvnw.cmd"), "@echo off\r\nexit /b 3\r\n", StandardCharsets.UTF_8);
        SpringBootProjectLauncher launcherWithLongTimeout = new SpringBootProjectLauncher(Duration.ofSeconds(30));

        long startedAt = System.currentTimeMillis();
        assertThatThrownBy(
                () -> launcherWithLongTimeout.launch(new GeneratedProject("p1", projectDir, "fakeProcess")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exited with code 3")
                .hasMessageContaining("launch.log");
        long elapsed = System.currentTimeMillis() - startedAt;

        assertThat(elapsed).as("should fail on the dead process, not wait out the 30s timeout").isLessThan(5_000);
        assertThat(launcherWithLongTimeout.find("p1")).isEmpty();
    }

    private static boolean isListening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // Caught by an adversarial review of the tests themselves: the previous version of this helper
    // polled for up to 15s and then just RETURNED whether or not the port had actually gone quiet,
    // so every caller that "verified" a stop was really only verifying that 15 seconds could
    // elapse. It has to fail the test when the port is still up, which is the whole point of it.
    private static void assertStopsListening(int port) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (!isListening(port)) {
                return;
            }
            sleep(200);
        }
        throw new AssertionError("Port " + port + " was still listening 15s after it should have been released");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // the Process handles the launcher is holding, read straight out of its own registry - the only
    // way from out here to tell "the OS really reaped this process tree" apart from "the launcher
    // stopped tracking it and the port happens to be quiet"
    @SuppressWarnings("unchecked")
    private static Process trackedProcess(SpringBootProjectLauncher launcher, String projectId) throws Exception {
        Field runningField = SpringBootProjectLauncher.class.getDeclaredField("running");
        runningField.setAccessible(true);
        Object entry = ((Map<String, ?>) runningField.get(launcher)).get(projectId);
        assertThat(entry).as("launcher is not tracking %s", projectId).isNotNull();
        Method processAccessor = entry.getClass().getDeclaredMethod("process");
        processAccessor.setAccessible(true);
        return (Process) processAccessor.invoke(entry);
    }
}
