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
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

// New scope item 4 (Spring Boot Generation)'s last piece: actually starting a project SpringBootProjectGenerator assembled on disk, as its own background process. Kept separate from that class for the same reason its own header comment gives - file assembly is trivially unit testable, starting a real child JVM is not, and mixing the two would have made every generator test slow and flaky. User's own explicit decision (asked directly, not assumed): support more than one generated app running at once, with ports assigned automatically rather than fixed - two generated projects both hardcoded to 8080 would just fail the second one to start. Tracked here, in memory, keyed by projectId; this is also the registry the future Evolve workflow step ("connect to an existing deployed application") will read from.
@Component
public class SpringBootProjectLauncher {

    private static final Logger logger = LoggerFactory.getLogger(SpringBootProjectLauncher.class);

    // Camunda's own engine bootstrap plus a cold Maven dependency resolution on a machine that hasn't run this exact generated project before can genuinely take the better part of a minute - proven empirically against the real template, not guessed at. 120s was the original figure from that measurement on a small demo process; a large real-world model (Citi Bank's wire transfer review, dozens of activities) has more to deploy and can genuinely need longer on a cold cache, so this is 5 minutes now rather than assume every generated project is as small as the one this was first measured against.
    private static final Duration DEFAULT_READY_TIMEOUT = Duration.ofMinutes(5);

    private final Map<String, Running> running = new ConcurrentHashMap<>();

    // One lock object per projectId, deliberately NOT ConcurrentHashMap.compute() on `running` itself. compute() would give the same atomicity in fewer lines, but it holds the map's own bin lock for the whole duration of the mapping function - and here that function spawns a child JVM and then waits up to the full readiness timeout on it, now several minutes. CHM bins are shared by hash, and a resize needs every bin, so an unrelated projectId that happens to hash into the same bin (or any writer arriving during a resize) would block behind a Maven startup that has nothing to do with it. Serialising unrelated launches is a worse bug than the race being fixed, so the lock is keyed on the projectId that actually needs it. These entries are never removed: a stop()ped project can be relaunched under the same id, so dropping the lock would reopen the race for exactly that case. Bounded by the number of distinct generated projects seen since startup, which is small and in-memory anyway. ReentrantLock rather than a plain Object monitor (which is what this was until retention needed it) purely because runIfIdle() below has to be able to give up instead of blocking - synchronized has no tryLock. The mutual exclusion launch() gets out of it is identical.
    private final Map<String, ReentrantLock> launchLocks = new ConcurrentHashMap<>();
    private final Duration readyTimeout;

    public SpringBootProjectLauncher() {
        this(DEFAULT_READY_TIMEOUT);
    }

    // package-private, not @Value-configurable - this exists so a test can prove the real timeout path without waiting out the real 5 minutes, not so an operator tunes it
    SpringBootProjectLauncher(Duration readyTimeout) {
        this.readyTimeout = readyTimeout;
    }

    private record Running(LaunchedProject info, Process process) {
    }

    // Caught by an adversarial review: stop -> findFreePort -> startProcess -> put used to be four separate steps, so two concurrent launches of the SAME projectId could both find nothing to stop, both spawn a child JVM, and the second put() would then overwrite the first one's entry - leaving that first process running with nothing in the map pointing at it. Unreachable via find()/listRunning(), so unkillable via stop(), and holding its port until the workbench itself dies. The whole sequence is now atomic per projectId; see launchLocks above for why it's a per-key lock rather than a compute() on the running map.
    public LaunchedProject launch(GeneratedProject project) {
        return launch(project, Map.of());
    }

    // extraEnv is additive, on top of the SERVER_PORT/SERVER_ADDRESS this method always sets - for a caller that needs the launched app started with a property the generated project's own application.properties leaves at its default, e.g. METAML_MESSAGING_ENABLED=true (Spring's relaxed env-var binding maps that to metaml.messaging.enabled) to demonstrate the real RabbitMQ path against a broker the caller has separately made available. The single-arg overload above is unchanged behavior for every existing caller.
    public LaunchedProject launch(GeneratedProject project, Map<String, String> extraEnv) {
        ReentrantLock lock = lockFor(project.projectId());
        lock.lock();
        try {
            // re-launching a project id that's already running would otherwise leak the old process and its port, orphaned with nothing left pointing at it
            stop(project.projectId());

            int port = findFreePort();
            Process process;
            try {
                process = startProcess(project.directory(), port, extraEnv);
            } catch (RuntimeException e) {
                throw attachPort(e, port);
            }
            try {
                awaitReady(process, project.directory(), port, readyTimeout);
            } catch (RuntimeException e) {
                destroyTree(process);
                throw attachPort(e, port);
            }

            // modelId filled in by WorkbenchServiceImpl, not here - this class only ever sees a GeneratedProject, which has no notion of "model"
            LaunchedProject info = new LaunchedProject(project.projectId(), project.processKey(), port,
                    Instant.now(), null, project.displayName());
            running.put(project.projectId(), new Running(info, process));
            logger.info("Launched generated project {} (process key '{}') on port {}",
                    project.projectId(), project.processKey(), port);
            return info;
        } finally {
            lock.unlock();
        }
    }

    private ReentrantLock lockFor(String projectId) {
        return launchLocks.computeIfAbsent(projectId, id -> new ReentrantLock());
    }

    // Retention safety gate. Runs `action` only while this projectId is provably idle - not running, and not in the middle of being launched - holding the same per-project lock launch() takes, so the answer cannot go stale between the check and the action. The in-flight half is the reason this exists at all rather than callers just asking find(). launch() only publishes into `running` AFTER awaitReady returns, which for a real generated project is up to five minutes of Maven and Camunda startup. For that entire window find() honestly reports "not running" while a child JVM is actively compiling and booting out of that very directory - so a cleanup that trusted find() alone would delete a project's source out from under its own starting process. Holding the launch lock is what closes that window; nothing else in this class distinguishes "idle" from "starting". tryLock, not lock: the whole point is that the caller is doing opportunistic cleanup on a request thread. Blocking here would mean a regenerate sitting behind a five-minute launch of the very project it was about to discard. Returning false instead just leaves the directory for the next lifecycle event (stop, another regenerate, restart) to collect - cleanup is always best-effort by design, never the thing that makes an operation fail.
    public boolean runIfIdle(String projectId, Runnable action) {
        if (projectId == null || projectId.isBlank()) {
            return false;
        }
        return runIfAllIdle(List.of(projectId), action);
    }

    // The all-or-nothing form, for a caller acting on a whole SET of projects at once - model deletion, which must not remove some of a model's generated projects and then discover another one is running. Same guarantee as runIfIdle scaled up: every project is proven idle, and stays idle, for the whole action. Locks are taken in sorted order purely as a discipline. Two concurrent model deletions can't actually contend (a generated project belongs to exactly one model), so this isn't fixing a live deadlock; it means a future caller with overlapping sets can't introduce one either. Liveness is checked only after ALL locks are held - checking as we went would let an already-checked project start launching while later ones were still being locked.
    public boolean runIfAllIdle(Collection<String> projectIds, Runnable action) {
        List<String> ordered = projectIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .sorted()
                .toList();
        List<ReentrantLock> held = new ArrayList<>(ordered.size());
        try {
            for (String projectId : ordered) {
                ReentrantLock lock = lockFor(projectId);
                if (!lock.tryLock()) {
                    // something is mid-launch; give up rather than block the caller behind it
                    return false;
                }
                held.add(lock);
            }
            for (String projectId : ordered) {
                // same liveness question find() already answers, asked while nothing can change it - and it self-heals a dead entry on the way past, exactly as it does for any other caller (see find()'s own comment); a project whose JVM died externally reads as idle here for the same reason it reads as not-running everywhere else
                if (find(projectId).isPresent()) {
                    return false;
                }
            }
            // an empty set is vacuously idle - a model that was never generated has nothing to guard, and its deletion should not be refused for lack of anything to check
            action.run();
            return true;
        } finally {
            for (int i = held.size() - 1; i >= 0; i--) {
                held.get(i).unlock();
            }
        }
    }

    // awaitReady already throws GeneratedProjectLaunchException with the port (and exit code, when known) attached directly - this only wraps failures from elsewhere in the launch path (right now, just startProcess itself failing to spawn anything at all) so every way launch() can fail carries the port it was attempting, not just the two most common ones
    private static RuntimeException attachPort(RuntimeException e, int port) {
        return e instanceof GeneratedProjectLaunchException ? e
                : new GeneratedProjectLaunchException(e.getMessage(), port, null, e);
    }

    // Query-time liveness, not a background daemon: awaitReady already proved isAlive() is the right signal for "did this JVM actually survive", this just asks the same question again on every read instead of trusting whatever launch() last observed. A generated app that dies on its own (OOM, an uncaught exception, someone kill -9ing it directly - proven live against a real generated project) leaves its port unreachable and its Process no longer alive; nothing about that requires polling in the background to detect, only checking before this class hands the entry to a caller who's about to act on "is this actually running".
    public Optional<LaunchedProject> find(String projectId) {
        Running r = running.get(projectId);
        if (r == null) {
            return Optional.empty();
        }
        if (!r.process().isAlive()) {
            forgetIfStillDead(projectId, r);
            return Optional.empty();
        }
        return Optional.of(r.info());
    }

    // the Evolve workflow step's own future read of "what's currently deployed to connect to" - same liveness re-check as find(), for the same reason: a dead entry here would otherwise read as a real, connectable application
    public List<LaunchedProject> listRunning() {
        List<LaunchedProject> alive = new ArrayList<>();
        for (Map.Entry<String, Running> entry : running.entrySet()) {
            Running r = entry.getValue();
            if (r.process().isAlive()) {
                alive.add(r.info());
            } else {
                forgetIfStillDead(entry.getKey(), r);
            }
        }
        return alive;
    }

    // Self-heals the registry the moment a dead entry is observed, rather than leaving it for stop() to eventually clear - conditional on value (remove(key, value), not remove(key)) so this can never delete a DIFFERENT, freshly-launched Running that raced in under the same projectId between the isAlive() check above and this call (launch()'s own per-key lock allows exactly that: stop-then-relaunch under one id is the documented, intended way to reuse a projectId).
    private void forgetIfStillDead(String projectId, Running observed) {
        if (running.remove(projectId, observed)) {
            logger.warn("Generated project {} was reported running but its process has died; "
                    + "removing the stale entry", projectId);
        }
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

    // Nothing else kills these. A generated app is a child JVM started with ProcessBuilder, which does NOT die with its parent on either Windows or Linux - so before this existed, every workbench restart during a demo left the previous run's generated apps alive, still holding their ports, with the new workbench instance having no record of them and no way to stop them. Spring calls this on normal context shutdown (Ctrl+C included, via the JVM shutdown hook Spring Boot registers); a hard kill -9 of the workbench still leaks them, which is the same residual gap any parent-side cleanup has and isn't fixable from here.
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

    // Process.destroy() alone only signals the immediate process - on Windows that's cmd.exe, not the actual java process the wrapper script spawns underneath it, which would otherwise keep the port bound after stop() returns. Process.descendants() (JDK 9+) is the portable fix: walk the whole tree, not just the one handle we started.
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

    // Restart-only liveness probe, for the one case the registry above genuinely cannot answer: a hard kill (kill -9 / taskkill /F) of the workbench leaves its generated JVMs running - they are ProcessBuilder children and do not die with the parent, and @PreDestroy never got to run - so the next workbench starts with an empty `running` map while those apps are still up and still holding their ports. Cleanup would then see "not running" and delete a live project's directory out from under it. Deliberately NOT consulted during normal operation. While the workbench is up, `running` is the single authority on liveness and a port probe could only disagree with it - a second, weaker source of truth is exactly the kind of thing that causes the bug it is meant to prevent. At startup there is nothing to disagree with: the map is empty by construction, so this only ever adds information. Fails closed on purpose. A listening port proves something is there, not that it is the generated app (the OS may have handed the port to an unrelated process since). Treating that as "may still be alive" over-retains a directory at worst, and over-retention is recoverable - the next regenerate or restart tries again - whereas deleting a running app's source is not. Uses the same loopback connect awaitReady already relies on, so it stays portable rather than shelling out to a platform-specific process lister.
    public boolean somethingIsListeningOn(int port) {
        if (port <= 0 || port > 65535) {
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 300);
            return true;
        } catch (IOException nothingThere) {
            return false;
        }
    }

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not find a free port to launch the generated project on", e);
        }
    }

    // SERVER_PORT rather than a CLI argument - Spring Boot's relaxed environment-variable binding picks this up as server.port with no extra plumbing, and it keeps this launcher's own command simple enough that a test can stand in a trivial fake for the real "mvnw spring-boot:run" without having to reproduce Maven's own argument parsing. The wrapper script is referenced by its full absolute path, not the bare "mvnw.cmd" - found empirically, not assumed: on this machine cmd.exe's own bare-command lookup does not fall back to the current directory the way it normally would (some environments set NoDefaultCurrentDirectoryInExePath or an equivalent hardening policy for this), so "cmd /c mvnw.cmd" with -WorkingDirectory pointed at the right place still failed with "'mvnw.cmd' is not recognized" even though `dir` in that same process proved the file was right there. An absolute path sidesteps the lookup question entirely rather than depending on a machine- specific cmd.exe policy nobody would think to check.
    private Process startProcess(Path projectDir, int port, Map<String, String> extraEnv) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String wrapper = projectDir.resolve(windows ? "mvnw.cmd" : "mvnw").toAbsolutePath().toString();
        boolean hasWrapper = Files.isRegularFile(Path.of(wrapper));
        // RedCollarTP supplies Maven wrapper metadata but not the wrapper scripts.  Keep the normal wrapper path for templates that have it, and use the installed Maven executable for this otherwise-complete target platform template. Only this path also gets an explicit build step first (see runMavenInstall) - every wrapper-based template/test here already exercises 'mvnw spring-boot:run' on its own, which compiles as part of its own default lifecycle.
        if (!hasWrapper) {
            runMavenInstall(projectDir);
        }
        List<String> command = hasWrapper
                ? (windows ? List.of("cmd.exe", "/c", wrapper, "spring-boot:run")
                        : List.of(wrapper, "spring-boot:run"))
                : List.of(mavenExecutable(), "spring-boot:run");
        try {
            Path logFile = projectDir.resolve("launch.log");
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(projectDir.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()))
                    .redirectErrorStream(true);
            builder.environment().put("SERVER_PORT", String.valueOf(port));
            builder.environment().putAll(extraEnv);
            // Same relaxed-binding mechanism as SERVER_PORT above, and set for a security reason rather than a functional one: Spring Boot binds every interface by default, so a generated project - which ships with the template's permissive dev security config and a Camunda engine behind it - was reachable from anything on the same network the moment it launched. Confirmed with Get-NetTCPConnection against a real generated project launched from templates/camundademo, not assumed: the listener came up on ":::<port>" (the dual-stack wildcard, i.e. all interfaces) without this line and on "127.0.0.1:<port>" with it. Worth noting the wildcard shows up as "::" rather than "0.0.0.0" on this machine, so a check that only looked for 0.0.0.0 would have missed the problem entirely.
            builder.environment().put("SERVER_ADDRESS", "127.0.0.1");
            return builder.start();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start generated project at " + projectDir.toAbsolutePath(), e);
        }
    }

    // Explicit build step, requested for the RedCollarTP-derived Target Platform launch flow: `mvn clean install -DskipTests` runs to completion BEFORE the run command starts, so a compile or dependency failure is reported here - plainly, with its own log - rather than only surfacing later as an opaque "never started listening on port N". Blocking, like the rest of startProcess's caller; launch() already treats the whole method as one unit of work.
    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(5);

    private void runMavenInstall(Path projectDir) {
        Path buildLog = projectDir.resolve("build.log");
        List<String> command = List.of(mavenExecutable(), "clean", "install", "-DskipTests");
        Process build;
        try {
            build = new ProcessBuilder(command)
                    .directory(projectDir.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.to(buildLog.toFile()))
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not run 'mvn clean install -DskipTests' for "
                    + projectDir.toAbsolutePath(), e);
        }
        boolean finished;
        try {
            finished = build.waitFor(BUILD_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            build.destroyForcibly();
            throw new IllegalStateException("Interrupted while running 'mvn clean install -DskipTests' for "
                    + projectDir.toAbsolutePath());
        }
        if (!finished) {
            build.destroyForcibly();
            throw new IllegalStateException("'mvn clean install -DskipTests' did not finish within "
                    + BUILD_TIMEOUT.toSeconds() + "s for " + projectDir.toAbsolutePath() + " - check "
                    + buildLog.toAbsolutePath());
        }
        if (build.exitValue() != 0) {
            throw new IllegalStateException("'mvn clean install -DskipTests' failed (exit " + build.exitValue()
                    + ") for " + projectDir.toAbsolutePath() + " - check " + buildLog.toAbsolutePath());
        }
        logger.info("'mvn clean install -DskipTests' finished for {}", projectDir.toAbsolutePath());
    }

    // An app launched from an IDE/service frequently inherits a much shorter PATH than an interactive terminal.  Resolve the standard Maven locations before falling back to PATH so a RedCollar-derived template without mvnw still launches on macOS/Homebrew and Linux.
    private static String mavenExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return resolveMavenExecutable(System.getenv("MAVEN_HOME"), windows);
    }

    // Split out from mavenExecutable() so a test can drive both branches without needing to fake the real environment - mavenExecutable() itself just reads System.getenv/getProperty and calls straight through. Confirmed by direct reproduction on Windows with MAVEN_HOME set to a real install: the old logic here looked for a file literally named "mvn" on every OS, including Windows - but Windows has no such launcher, only "mvn.cmd". The official distribution's zip ships an extensionless "mvn" in the same bin/ folder regardless of platform (the POSIX shell script, inert on Windows), and Files.isExecutable() has no POSIX-permission concept on Windows, so it reports that file as executable too - meaning MAVEN_HOME/bin/mvn was found and returned before MAVEN_HOME/bin/mvn.cmd was ever checked. ProcessBuilder then fails inside start() itself (CreateProcess error=193, "%1 is not a valid Win32 application") before any child process exists, which is why the resulting build.log/launch.log is empty rather than containing a Maven error - reproduced verbatim against this exact failure mode before this fix. mvn.cmd needs no special wrapping to run correctly via ProcessBuilder once it's the one actually resolved - confirmed directly, not assumed.
    static String resolveMavenExecutable(String mavenHome, boolean windows) {
        String mvnName = windows ? "mvn.cmd" : "mvn";
        if (mavenHome != null && !mavenHome.isBlank()) {
            Path candidate = Path.of(mavenHome, "bin", mvnName);
            if (Files.isExecutable(candidate)) return candidate.toString();
        }
        // Homebrew/Linux install locations only - meaningless on Windows, where Maven is never installed at a Unix absolute path.
        if (!windows) {
            for (String candidate : List.of("/opt/homebrew/bin/mvn", "/usr/local/bin/mvn", "/usr/bin/mvn")) {
                if (Files.isExecutable(Path.of(candidate))) return candidate;
            }
        }
        return mvnName;
    }

    // polls rather than trusting a fixed sleep - a cold Maven dependency download takes nothing like a warm one, so a fixed wait would either be too slow for the common case or too short for the first run on a fresh machine Takes the Process because "not listening yet" and "already dead" are not the same thing, and this used to treat them identically: a generated project that failed to COMPILE - the single most likely failure in a live demo, since the delegates are generated from whatever the user modelled - exited within seconds, and the caller then sat here for the full readiness timeout before reporting anything that said why. Checking isAlive() each round turns that into an immediate failure carrying the exit code and the log to go read.
    private static void awaitReady(Process process, Path projectDir, int port, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("localhost", port), 500);
                return;
            } catch (IOException notReadyYet) {
                // inside the failed-connect branch rather than at the top of the loop: listening is the success condition, so a process that got the port up and then exited between two polls should still count as started rather than be failed on liveness
                if (!process.isAlive()) {
                    throw new GeneratedProjectLaunchException("Generated project at " + projectDir.toAbsolutePath()
                            + " exited with code " + process.exitValue()
                            + " before it started listening on port " + port
                            + " - check " + projectDir.resolve("launch.log").toAbsolutePath(),
                            port, process.exitValue(), null);
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new GeneratedProjectLaunchException("Interrupted while waiting for the generated "
                            + "project to start", port, null, e);
                }
            }
        }
        throw new GeneratedProjectLaunchException("Generated project did not start listening on port " + port
                + " within " + timeout.getSeconds() + "s - check its launch.log", port, null, null);
    }
}
