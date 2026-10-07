package io.renova.java;

import io.renova.core.engine.BuildError;
import io.renova.java.fix.MavenVerifierAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MavenParsingTest {

    @Test
    void readsJavaLevelFromCompilerPluginAndInterpolatesVersions(@TempDir Path dir) throws Exception {
        Path pom = dir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <artifactId>app</artifactId><groupId>g</groupId><version>1</version>
                  <properties><lib.version>2.5</lib.version></properties>
                  <dependencies>
                    <dependency><groupId>x</groupId><artifactId>lib</artifactId><version>${lib.version}</version></dependency>
                  </dependencies>
                  <build><plugins><plugin>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <configuration><source>1.7</source><target>1.7</target></configuration>
                  </plugin></plugins></build>
                </project>
                """);
        PomReader.Pom read = PomReader.read(pom);
        assertThat(read.javaVersion()).isEqualTo("7");
        assertThat(read.dependencies()).extracting(PomReader.Dependency::coordinates).containsExactly("x:lib:2.5");
    }

    @Test
    void interpolationLeavesUnknownPropertiesAlone() {
        assertThat(PomReader.interpolate("${a}-${missing}", Map.of("a", "1"))).isEqualTo("1-${missing}");
    }

    @Test
    void parsesCompilerErrorsRelativeToWorkspace() {
        Path ws = Path.of("/work/ws");
        String output = """
                [INFO] Compiling 3 source files
                [ERROR] /work/ws/app/src/main/java/A.java:[12,8] cannot find symbol
                [ERROR] /work/ws/app/src/main/java/A.java:[12,8] cannot find symbol
                [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.1:compile (default-compile) on project app: Compilation failure: Compilation failure:
                """;
        List<BuildError> errors = MavenVerifierAccess.parse(output, ws, ws.resolve("app"));
        assertThat(errors).containsExactly(new BuildError("app/src/main/java/A.java", 12, "cannot find symbol"));
    }

    @Test
    void attributesBuildConfigurationFailuresToThePom() {
        Path ws = Path.of("/work/ws");
        String output = "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.1:compile "
                + "(default-compile) on project app: Fatal error compiling: warning: source release 21 requires target release 21 -> [Help 1]";
        assertThat(MavenVerifierAccess.parse(output, ws, ws.resolve("app"))).containsExactly(new BuildError(
                "app/pom.xml", 0, "Fatal error compiling: warning: source release 21 requires target release 21"));
    }

    @Test
    void aGoalFailureWithoutWordsIsNotAnEmptyError() {
        Path ws = Path.of("/work/ws");
        // Surefire 3.5: the reason is on the lines after, and the test reports carry the failures.
        String tests = "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.4:test (default-test) "
                + "on project app: \n[ERROR] \n[ERROR] See /work/ws/app/target/surefire-reports for the individual test results.";
        assertThat(MavenVerifierAccess.parse(tests, ws, ws.resolve("app"))).isEmpty();
        String other = "[ERROR] Failed to execute goal com.acme:pack-maven-plugin:1.2:pack (default) on project app:  -> [Help 1]";
        assertThat(MavenVerifierAccess.parse(other, ws, ws.resolve("app"))).containsExactly(new BuildError(
                "app/pom.xml", 0, "goal com.acme:pack-maven-plugin:1.2:pack (default) failed"));
    }

    @Test
    void attributesProjectModelErrorsToTheModulePomAndLine() {
        Path ws = Path.of("/work/ws");
        String output = """
                [ERROR] [ERROR] Some problems were encountered while processing the POMs:
                [ERROR] 'dependencies.dependency.version' for org.example:lib:jar is missing. @ line 39, column 21
                [ERROR] The build could not read 1 project -> [Help 1]
                [ERROR]  \s
                [ERROR]   The project g:web:1.0 (/work/ws/web/pom.xml) has 1 error
                [ERROR]     'dependencies.dependency.version' for org.example:lib:jar is missing. @ line 39, column 21
                """;
        assertThat(MavenVerifierAccess.parse(output, ws, ws)).containsExactly(new BuildError("web/pom.xml", 39,
                "'dependencies.dependency.version' for org.example:lib:jar is missing."));
    }
}
