package io.renova.java.fix;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MavenWrapperTest {

    private static Path wrapper(Path root, String version) throws Exception {
        Path file = Files.createDirectories(root.resolve(".mvn/wrapper")).resolve("maven-wrapper.properties");
        Files.writeString(file, "distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/" + version
                + "/apache-maven-" + version + "-bin.zip\nwrapperUrl=https://example.org/maven-wrapper-0.5.4.jar\n");
        return file;
    }

    @Test
    void anOldWrapperIsMovedToACurrentMaven(@TempDir Path root) throws Exception {
        Path file = wrapper(root, "3.5.4");
        assertThat(MavenSupport.upgradeWrapper(root)).contains("3.5.4 → 3.9.9");
        assertThat(Files.readString(file)).contains("/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip", "wrapperUrl=https://example.org");
    }

    @Test
    void aWrapperThatIsNewEnoughOrMissingIsLeftAlone(@TempDir Path root) throws Exception {
        assertThat(MavenSupport.upgradeWrapper(root)).isNull();
        Path file = wrapper(root, "3.8.6");
        assertThat(MavenSupport.upgradeWrapper(root)).isNull();
        assertThat(Files.readString(file)).contains("3.8.6");
    }
}
