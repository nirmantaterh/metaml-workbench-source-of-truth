package com.metaml.workbench.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

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

    @TempDir
    Path projectDir;

    private final SpringBootProjectLauncher launcher = new SpringBootProjectLauncher();

    private GeneratedProject fakeProject(String projectId) throws IOException {
        // PowerShell TCP listener standing in for a real Spring Boot app - reads the same
        // SERVER_PORT the launcher sets for the real command, so this fake never has to know
        // anything about Maven's own argument parsing
        Files.writeString(projectDir.resolve("mvnw.cmd"), """
                @echo off
                powershell -NoProfile -Command "$l = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, [int]$env:SERVER_PORT); $l.Start(); Start-Sleep -Seconds 300; $l.Stop()"
                """, StandardCharsets.UTF_8);
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
        Files.writeString(secondDir.resolve("mvnw.cmd"), """
                @echo off
                powershell -NoProfile -Command "$l = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, [int]$env:SERVER_PORT); $l.Start(); Start-Sleep -Seconds 300; $l.Stop()"
                """, StandardCharsets.UTF_8);
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
        awaitNotListening(launched.port());
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
        awaitNotListening(first.port());
        assertThat(isListening(second.port())).isTrue();

        launcher.stop("p1");
    }

    // the real launch() path, not a reimplementation of its polling loop - a 2-second launcher
    // (rather than the production 120s one) makes it affordable to actually let this run to its
    // real failure instead of only inspecting the source for a throw statement
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

    private static boolean isListening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void awaitNotListening(int port) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (!isListening(port)) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
