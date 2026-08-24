package com.metaml.workbench.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Regression coverage for the Windows launch bug reproduced directly on a real Windows machine
// with a real Maven install: SpringBootProjectLauncher.resolveMavenExecutable(mavenHome, windows)
// is the pure, parameterized core of mavenExecutable() (which just reads the real environment and
// calls straight through) - split out specifically so this can drive both the Windows and
// non-Windows branches deterministically, on any OS the build actually runs on, without needing a
// real Windows machine or a real MAVEN_HOME to prove the fix.
//
// The bug: the official Maven distribution ships BOTH an extensionless "mvn" (a POSIX shell
// script, present on every platform's copy of the zip) and "mvn.cmd" (the real Windows launcher)
// in the same bin/ folder. Files.isExecutable() has no POSIX-permission concept on Windows, so it
// reports the POSIX script as executable too - and the old logic checked for "mvn" unconditionally,
// finding and returning that script before mvn.cmd was ever considered. ProcessBuilder then fails
// inside start() itself (CreateProcess error=193, "%1 is not a valid Win32 application"), which is
// why the resulting build.log is empty rather than containing an actual Maven error - reproduced
// verbatim on a real Windows machine before this fix landed.
class SpringBootProjectLauncherMavenExecutableTest {

    @TempDir
    Path mavenHome;

    private Path bin() {
        return mavenHome.resolve("bin");
    }

    private void writeExecutable(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "placeholder");
        // best-effort on Windows (no POSIX x-bit there), the real setExecutable(true) that matters
        // on Unix - matches how Files.isExecutable() actually gets satisfied on each platform.
        file.toFile().setExecutable(true);
    }

    @Test
    void windowsPrefersMvnCmdOverTheExtensionlessPosixScriptWhenBothExist() throws IOException {
        // Both files present in the same bin/ - exactly what the real Maven Windows distribution
        // ships - so a naive "check for mvn" would find the wrong one first, same as the real bug.
        writeExecutable(bin().resolve("mvn"));
        writeExecutable(bin().resolve("mvn.cmd"));

        String resolved = SpringBootProjectLauncher.resolveMavenExecutable(mavenHome.toString(), true);

        assertThat(resolved).endsWith("mvn.cmd");
        assertThat(resolved).isEqualTo(bin().resolve("mvn.cmd").toString());
    }

    @Test
    void windowsFallsBackToBareMvnCmdWhenMavenHomeHasNoBinDirectory() {
        // MAVEN_HOME set but pointing somewhere with no bin/mvn.cmd at all - must not return the
        // Unix "mvn" bare fallback on Windows, since that never resolves via ProcessBuilder either
        // (reproduced separately: bare "mvn" fails with CreateProcess error=2 even with Maven's
        // bin/ on PATH).
        String resolved = SpringBootProjectLauncher.resolveMavenExecutable(mavenHome.toString(), true);

        assertThat(resolved).isEqualTo("mvn.cmd");
    }

    @Test
    void windowsFallsBackToBareMvnCmdWhenMavenHomeIsNotSet() {
        String resolved = SpringBootProjectLauncher.resolveMavenExecutable(null, true);

        assertThat(resolved).isEqualTo("mvn.cmd");
    }

    @Test
    void nonWindowsStillResolvesTheExtensionlessMvnScript() throws IOException {
        // Existing macOS/Linux behavior must be unchanged: no ".cmd" anywhere in the picture.
        writeExecutable(bin().resolve("mvn"));

        String resolved = SpringBootProjectLauncher.resolveMavenExecutable(mavenHome.toString(), false);

        assertThat(resolved).isEqualTo(bin().resolve("mvn").toString());
        assertThat(resolved).doesNotContain(".cmd");
    }

    @Test
    void nonWindowsFallsBackToBareMvnWhenMavenHomeIsNotSet() {
        String resolved = SpringBootProjectLauncher.resolveMavenExecutable(null, false);

        assertThat(resolved).isEqualTo("mvn");
    }
}
