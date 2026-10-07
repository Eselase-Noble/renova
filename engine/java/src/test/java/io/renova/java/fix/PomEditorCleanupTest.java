package io.renova.java.fix;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PomEditorCleanupTest {

    private static final String POM = """
            <project>
                <properties>
                    <java.version>21</java.version>
                    <assertj.version>3.17.1</assertj.version>
                </properties>
                <dependencies>
                    <dependency>
                        <groupId>javax.validation</groupId>
                        <artifactId>validation-api</artifactId>
                        <version>2.0.1.Final</version>
                    </dependency>
                </dependencies>
                <build>
                    <plugins>
                        <plugin>
                            <artifactId>maven-surefire-plugin</artifactId>
                            <version>3.2.5</version>
                            <dependencies>
                                <dependency>
                                    <groupId>org.junit.platform</groupId>
                                    <artifactId>junit-platform-surefire-provider</artifactId>
                                    <version>1.1.0</version>
                                </dependency>
                            </dependencies>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """;

    @Test
    void aPinnedVersionIsRemovedWithItsLine() {
        PomEditor.Result r = PomEditor.removeProperty(POM, "assertj.version");
        assertThat(r.changes()).isEqualTo(1);
        assertThat(r.content()).contains("<java.version>21</java.version>\n    </properties>").doesNotContain("assertj");
        assertThat(PomEditor.removeProperty(POM, "mockito.version").changes()).isZero();
    }

    @Test
    void aPluginLosesTheObsoleteProviderAndTheEmptyList() {
        PomEditor.Result r = PomEditor.removePluginDependency(POM, "junit-platform-surefire-provider");
        assertThat(r.changes()).isEqualTo(1);
        assertThat(r.content()).contains("<version>3.2.5</version>\n            </plugin>").doesNotContain("surefire-provider")
                // The project's own dependencies are not a plugin's.
                .contains("<artifactId>validation-api</artifactId>");
    }

    @Test
    void aDependencyBecomesItsSuccessorWithoutAVersion() {
        PomEditor.Result r = PomEditor.replaceDependency(POM, (g, a) -> (g + ":" + a).equals("javax.validation:validation-api"),
                "jakarta.validation", "jakarta.validation-api", null);
        assertThat(r.content()).contains("<artifactId>jakarta.validation-api</artifactId>\n        </dependency>")
                .doesNotContain("2.0.1.Final");
    }
}
