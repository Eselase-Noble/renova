package io.renova.java.fix;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PomEditorVersionTest {

    private static final String POM = """
            <project>
              <properties>
                <servlet.version>6.0.0</servlet.version>
              </properties>
              <dependencies>
                <dependency>
                  <groupId>jakarta.servlet</groupId>
                  <artifactId>jakarta.servlet-api</artifactId>
                  <version>%s</version>
                  <scope>provided</scope>
                </dependency>
                <dependency>
                  <groupId>jakarta.el</groupId>
                  <artifactId>jakarta.el-api</artifactId>
                  <version>5.0.0</version>
                </dependency>
              </dependencies>
            </project>
            """;

    @Test
    void aVersionWrittenInPlaceIsReplaced() {
        PomEditor.Result r = PomEditor.changeDependencyVersion(POM.formatted("6.0.0"), "jakarta.servlet", "jakarta.servlet-api", "6.1.0");
        assertThat(r.changes()).isEqualTo(1);
        assertThat(r.content()).contains("<artifactId>jakarta.servlet-api</artifactId>\n      <version>6.1.0</version>\n      <scope>provided</scope>")
                .contains("<version>5.0.0</version>").contains("<servlet.version>6.0.0</servlet.version>");
    }

    @Test
    void aVersionGivenByAPropertyChangesTheProperty() {
        PomEditor.Result r = PomEditor.changeDependencyVersion(POM.formatted("${servlet.version}"), "jakarta.servlet", "jakarta.servlet-api", "6.1.0");
        assertThat(r.changes()).isEqualTo(1);
        assertThat(r.content()).contains("<servlet.version>6.1.0</servlet.version>").contains("<version>${servlet.version}</version>");
    }

    @Test
    void nothingChangesWhenItIsAlreadyThere() {
        String pom = POM.formatted("6.1.0");
        assertThat(PomEditor.changeDependencyVersion(pom, "jakarta.servlet", "jakarta.servlet-api", "6.1.0").content()).isEqualTo(pom);
        assertThat(PomEditor.changeDependencyVersion(pom, "jakarta.servlet", "jakarta.servlet-api", "6.1.0").changes()).isZero();
    }
}
