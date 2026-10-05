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
}
