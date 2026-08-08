package com.metaml.workbench.generation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

// New scope item 4 (Spring Boot Generation)'s last piece: actually starting a project
// SpringBootProjectGenerator assembled on disk, as its own background process. Kept separate from
// that class for the same reason its own header comment gives - file assembly is trivially unit
// testable, starting a real child JVM is not, and mixing the two would have made every generator
// test slow and flaky.
//
// User's own explicit decision (asked directly, not assumed): support more than one generated
// app running at once, with ports assigned automatically rather than fixed - two generated
// projects both hardcoded to 8080 would just fail the second one to start. Tracked here, in
// memory, keyed by projectId; this is also the registry the future Evolve workflow step ("connect
// to an existing deployed application") will read from.
@Component
public class SpringBootProjectLauncher {

    private static final Logger logger = LoggerFactory.getLogger(SpringBootProjectLauncher.class);

    // Camunda's own engine bootstrap plus a cold Maven dependency resolution on a machine that
    // hasn't run this exact generated project before can genuinely take the better part of a
    // minute - proven empirically against the real template, not guessed at.
    private static final Duration DEFAULT_READY_TIMEOUT = Duration.ofSeconds(120);

    private final Map<String, Running> running = new ConcurrentHashMap<>();

    // One lock object per projectId, deliberately NOT ConcurrentHashMap.compute() on `running`
    // itself. compute() would give the same atomicity in fewer lines, but it holds the map's own
    // bin lock for the whole duration of the mapping function - and here that function spawns a
    // child JVM and then waits up to the full 120s readiness timeout on it. CHM bins are shared by
    // hash, and a resize needs every bin, so an unrelated projectId that happens to hash into the
    // same bin (or any writer arriving during a resize) would block behind a two-minute Maven
    // startup it has nothing to do with. Serialising unrelated launches is a worse bug than the
    // race being fixed, so the lock is keyed on the projectId that actually needs it.
    //
    // These entries are never removed: a stop()ped project can be relaunched under the same id, so
    // dropping the lock would reopen the race for exactly that case. Bounded by the number of
    // distinct generated projects seen since startup, which is small and in-memory anyway.
    private final Map<String, Object> launchLocks = new ConcurrentHashMap<>();
    private final Duration readyTimeout;

    public SpringBootProjectLauncher() {
        this(DEFAULT_READY_TIMEOUT);
    }

    // package-private, not @Value-configurable - this exists so a test can prove the real timeout
    // path without waiting out the real 120s, not so an operator tunes it
    SpringBootProjectLauncher(Duration readyTimeout) {
        this.readyTimeout = readyTimeout;
    }

    private record Running(LaunchedProject info, Process process) {
    }

    // Caught by an adversarial review: stop -> findFreePort -> startProcess -> put used to be four
    // separate steps, so two concurrent launches of the SAME projectId could both find nothing to
    // stop, both spawn a child JVM, and the second put() would then overwrite the first one's entry
    // - leaving that first process running with nothing in the map pointing at it. Unreachable via
    // find()/listRunning(), so unkillable via stop(), and holding its port until the workbench
    // itself dies. The whole sequence is now atomic per projectId; see launchLocks above for why
    // it's a per-key lock rather than a compute() on the running map.
    public LaunchedProject launch(GeneratedProject project) {
        synchronized (launchLocks.computeIfAbsent(project.projectId(), id -> new Object())) {
            // re-launching a project id that's already running would otherwise leak the old process
            // and its port, orphaned with nothing left pointing at it
            stop(project.projectId());

            int port = findFreePort();
            Process process = startProcess(project.directory(), port);
            try {
                awaitReady(process, project.directory(), port, readyTimeout);
            } catch (RuntimeException e) {
                destroyTree(process);
                throw e;
            }

            LaunchedProject info = new LaunchedProject(project.projectId(), project.processKey(), port, Instant.now());
            running.put(project.projectId(), new Running(info, process));
            logger.info("Launched generated project {} (process key '{}') on port {}",
                    project.projectId(), project.processKey(), port);
            return info;
        }
    }

    public Optional<LaunchedProject> find(String projectId) {
        return Optional.ofNullable(running.get(projectId)).map(Running::info);
    }

    // the Evolve workflow step's own future read of "what's currently deployed to connect to"
    public List<LaunchedProject> listRunning() {
        return running.values().stream().map(Running::info).toList();
    }

    public boolean stop(String projectId) {
        Running r = running.remove(projectId);
        if (r == null) {
            return false;
        }
        destroyTree(r.process());
        logger.info("Stopped generated project {}", projectId);
        return true;
    }

    // Nothing else kills these. A generated app is a child JVM started with ProcessBuilder, which
    // does NOT die with its parent on either Windows or Linux - so before this existed, every
    // workbench restart during a demo left the previous run's generated apps alive, still holding
    // their ports, with the new workbench instance having no record of them and no way to stop
    // them. Spring calls this on normal context shutdown (Ctrl+C included, via the JVM shutdown
    // hook Spring Boot registers); a hard kill -9 of the workbench still leaks them, which is the
    // same residual gap any parent-side cleanup has and isn't fixable from here.
    @PreDestroy
    void stopEverythingStillRunning() {
        for (String projectId : List.copyOf(running.keySet())) {
            try {
                stop(projectId);
            } catch (RuntimeException e) {
                // one project refusing to die shouldn't strand the rest of them
                logger.warn("Could not stop generated project {} during shutdown: {}", projectId, e.toString());
            }
        }
    }

    // Process.destroy() alone only signals the immediate process - on Windows that's cmd.exe, not
    // the actual java process the wrapper script spawns underneath it, which would otherwise keep
    // the port bound after stop() returns. Process.descendants() (JDK 9+) is the portable fix:
    // walk the whole tree, not just the one handle we started.
    private static void destroyTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not find a free port to launch the generated project on", e);
        }
    }

    // SERVER_PORT rather than a CLI argument - Spring Boot's relaxed environment-variable binding
    // picks this up as server.port with no extra plumbing, and it keeps this launcher's own
    // command simple enough that a test can stand in a trivial fake for the real "mvnw
    // spring-boot:run" without having to reproduce Maven's own argument parsing.
    //
    // The wrapper script is referenced by its full absolute path, not the bare "mvnw.cmd" - found
    // empirically, not assumed: on this machine cmd.exe's own bare-command lookup does not fall
    // back to the current directory the way it normally would (some environments set
    // NoDefaultCurrentDirectoryInExePath or an equivalent hardening policy for this), so "cmd /c
    // mvnw.cmd" with -WorkingDirectory pointed at the right place still failed with "'mvnw.cmd' is
    // not recognized" even though `dir` in that same process proved the file was right there. An
    // absolute path sidesteps the lookup question entirely rather than depending on a machine-
    // specific cmd.exe policy nobody would think to check.
    private Process startProcess(Path projectDir, int port) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String wrapper = projectDir.resolve(windows ? "mvnw.cmd" : "mvnw").toAbsolutePath().toString();
        List<String> command = windows
                ? List.of("cmd.exe", "/c", wrapper, "spring-boot:run")
                : List.of(wrapper, "spring-boot:run");
        try {
            Path logFile = projectDir.resolve("launch.log");
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(projectDir.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()))
                    .redirectErrorStream(true);
            builder.environment().put("SERVER_PORT", String.valueOf(port));
            // Same relaxed-binding mechanism as SERVER_PORT above, and set for a security reason
            // rather than a functional one: Spring Boot binds every interface by default, so a
            // generated project - which ships with the template's permissive dev security config
            // and a Camunda engine behind it - was reachable from anything on the same network the
            // moment it launched. Confirmed with Get-NetTCPConnection against a real generated
            // project launched from templates/camundademo, not assumed: the listener came up on
            // ":::<port>" (the dual-stack wildcard, i.e. all interfaces) without this line and on
            // "127.0.0.1:<port>" with it. Worth noting the wildcard shows up as "::" rather than
            // "0.0.0.0" on this machine, so a check that only looked for 0.0.0.0 would have missed
            // the problem entirely.
            builder.environment().put("SERVER_ADDRESS", "127.0.0.1");
            return builder.start();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start generated project at " + projectDir.toAbsolutePath(), e);
        }
    }

    // polls rather than trusting a fixed sleep - a cold Maven dependency download takes nothing
    // like a warm one, so a fixed wait would either be too slow for the common case or too short
    // for the first run on a fresh machine
    //
    // Takes the Process because "not listening yet" and "already dead" are not the same thing, and
    // this used to treat them identically: a generated project that failed to COMPILE - the single
    // most likely failure in a live demo, since the delegates are generated from whatever the user
    // modelled - exited within seconds, and the caller then sat here for the full two minutes
    // before reporting a timeout that said nothing about why. Checking isAlive() each round turns
    // that into an immediate failure carrying the exit code and the log to go read.
    private static void awaitReady(Process process, Path projectDir, int port, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("localhost", port), 500);
                return;
            } catch (IOException notReadyYet) {
                // inside the failed-connect branch rather than at the top of the loop: listening is
                // the success condition, so a process that got the port up and then exited between
                // two polls should still count as started rather than be failed on liveness
                if (!process.isAlive()) {
                    throw new IllegalStateException("Generated project at " + projectDir.toAbsolutePath()
                            + " exited with code " + process.exitValue()
                            + " before it started listening on port " + port
                            + " - check " + projectDir.resolve("launch.log").toAbsolutePath());
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for the generated project to start",
                            e);
                }
            }
        }
        throw new IllegalStateException("Generated project did not start listening on port " + port
                + " within " + timeout.getSeconds() + "s - check its launch.log");
    }
}
