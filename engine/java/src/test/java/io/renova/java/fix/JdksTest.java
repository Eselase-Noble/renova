package io.renova.java.fix;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Finding the installed JDKs and choosing one for each step of a migration. */
class JdksTest {

    private static Path jdk(Path home, String version) throws Exception {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin/javac"), "");
        Files.writeString(home.resolve("release"), "IMPLEMENTOR=\"Acme\"\nJAVA_VERSION=\"" + version + "\"\n");
        return home;
    }

    @Test
    void findsJdksInTheUsualPlacesAndReadsTheirVersion(@TempDir Path home) throws Exception {
        jdk(home.resolve(".jdks/azul-17.0.16"), "17.0.16");
        jdk(home.resolve(".jdks/azul-1.8.0_462"), "1.8.0_462");
        jdk(home.resolve(".sdkman/candidates/java/25.0.1-tem"), "25.0.1");
        jdk(home.resolve("elsewhere/jdk-11"), "11.0.2");
        // A runtime without a compiler is not a JDK, and a folder without a release file says nothing.
        Files.createDirectories(home.resolve(".jdks/jre-21/bin"));
        Files.writeString(home.resolve(".jdks/jre-21/release"), "JAVA_VERSION=\"21\"\n");

        List<Jdks.Jdk> found = Jdks.installed(null, home, home.resolve("elsewhere").toString());
        assertThat(found).extracting(Jdks.Jdk::feature).contains(8, 11, 17, 25);
        assertThat(found).filteredOn(j -> j.home().startsWith(home)).extracting(Jdks.Jdk::feature).containsExactlyInAnyOrder(8, 11, 17, 25);
    }

    @Test
    void theTargetIsBuiltOnItsOwnJdkOrTheNextNewerOne() {
        List<Jdks.Jdk> jdks = List.of(new Jdks.Jdk(Path.of("/j/8"), 8), new Jdks.Jdk(Path.of("/j/17"), 17),
                new Jdks.Jdk(Path.of("/j/25"), 25), new Jdks.Jdk(Path.of("/j/21"), 21));
        assertThat(Jdks.forTarget(jdks, 21)).map(Jdks.Jdk::feature).contains(21);
        assertThat(Jdks.forTarget(jdks, 22)).map(Jdks.Jdk::feature).contains(25);
        assertThat(Jdks.forTarget(jdks, 26)).isEmpty();
        assertThat(Jdks.forTarget(jdks, 0)).map(Jdks.Jdk::feature).contains(8);
    }

    @Test
    void anOldGradleRunsOnTheNewestJdkItSupports() {
        List<Jdks.Jdk> jdks = List.of(new Jdks.Jdk(Path.of("/j/8"), 8), new Jdks.Jdk(Path.of("/j/17"), 17), new Jdks.Jdk(Path.of("/j/21"), 21));
        assertThat(Jdks.newestJavaFor("7.5.1")).isEqualTo(18);
        assertThat(Jdks.forGradle(jdks, "7.5.1")).map(Jdks.Jdk::feature).contains(17);
        assertThat(Jdks.forGradle(jdks, "8.5")).map(Jdks.Jdk::feature).contains(21);
        assertThat(Jdks.forGradle(jdks, "6.9.4")).map(Jdks.Jdk::feature).contains(8);
        assertThat(Jdks.forGradle(List.of(new Jdks.Jdk(Path.of("/j/21"), 21)), "7.5.1")).isEmpty();
        assertThat(Jdks.newestJavaFor("9.1.0")).isEqualTo(25);
        assertThat(Jdks.gradleFor(21)).startsWith("8.14");
        assertThat(Jdks.gradleFor(25)).startsWith("9.");
    }

    @Test
    void aWrapperTooOldForTheTargetIsMovedOn(@TempDir Path root) throws Exception {
        Path properties = Files.createDirectories(root.resolve("gradle/wrapper")).resolve("gradle-wrapper.properties");
        Files.writeString(properties, """
                distributionBase=GRADLE_USER_HOME
                distributionSha256Sum=abc123
                distributionUrl=https\\://services.gradle.org/distributions/gradle-7.5.1-bin.zip
                zipStoreBase=GRADLE_USER_HOME
                """);
        assertThat(GradleSupport.wrapperVersion(root)).isEqualTo("7.5.1");
        assertThat(GradleSupport.upgradeWrapper(root, 17)).isNull();
        assertThat(GradleSupport.upgradeWrapper(root, 21)).contains("7.5.1", "8.14.3", "Java 21");
        assertThat(Files.readString(properties)).contains("gradle-8.14.3-bin.zip").doesNotContain("distributionSha256Sum")
                .contains("zipStoreBase=GRADLE_USER_HOME");
        assertThat(GradleSupport.upgradeWrapper(root, 21)).isNull();
        assertThat(GradleSupport.wrapperVersion(root.resolve("no-wrapper"))).isNull();
    }
}
