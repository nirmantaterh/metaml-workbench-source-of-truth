package com.metaml.workbench.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Item 3's explicit requirement ('mvn clean install -DskipTests' before 'mvnw spring-boot:run')
// only applies to the no-wrapper (RedCollarTP-derived Target Platform) launch path - see
// SpringBootProjectLauncher.startProcess, which is exercised end to end (with the real launch
// that follows) by TargetHarnessPlatformEndToEndTest-style suites. runMavenInstall itself is
// tested directly here via reflection, the same way this test class already reaches into private
// launcher state elsewhere, so a build failure is proven to surface plainly and fast rather than
// only as a much later, harder-to-diagnose "never started listening on port N".
class SpringBootProjectLauncherMavenInstallTest {

    @TempDir
    Path projectDir;

    private static void invokeRunMavenInstall(Path dir) throws Throwable {
        try {
            Method method = SpringBootProjectLauncher.class.getDeclaredMethod("runMavenInstall", Path.class);
            method.setAccessible(true);
            method.invoke(new SpringBootProjectLauncher(), dir);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    void aBrokenPomFailsFastWithAClearMessageAndABuildLogToCheck() {
        // Malformed XML fails at parse time, before any dependency resolution - fast and
        // network-independent, unlike a real compile/install.
        writePom("<project>not valid xml");

        assertThatThrownBy(() -> invokeRunMavenInstall(projectDir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mvn clean install -DskipTests")
                .hasMessageContaining("build.log");
        assertThat(projectDir.resolve("build.log")).exists();
    }

    // Tagged slow like the other real-Maven-build suites here (RedCollarEndToEndTest etc.) - a
    // genuine 'mvn clean install' needs the local/remote plugin resolution those already assume.
    @Tag("slow")
    @Test
    void aValidPomInstallsCleanlyAndLeavesABuildLogBehind() throws Throwable {
        writePom("""
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.metaml.test</groupId>
                    <artifactId>maven-install-smoke-test</artifactId>
                    <version>0.0.1</version>
                    <packaging>pom</packaging>
                </project>
                """);

        invokeRunMavenInstall(projectDir);

        assertThat(projectDir.resolve("build.log")).exists();
        assertThat(Files.readString(projectDir.resolve("build.log"))).contains("BUILD SUCCESS");
    }

    private void writePom(String content) {
        try {
            Files.writeString(projectDir.resolve("pom.xml"), content, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
