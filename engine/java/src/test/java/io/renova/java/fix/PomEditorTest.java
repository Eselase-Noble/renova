package io.renova.java.fix;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PomEditorTest {

    private static final String POM = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <artifactId>app</artifactId>
              <packaging>war</packaging>

              <properties>
                <maven.compiler.source>21</maven.compiler.source>
              </properties>

              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>jakarta.servlet</groupId>
                    <artifactId>jakarta.servlet-api</artifactId>
                    <version>6.0.0</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>

              <dependencies>
                <!-- servlet API: keep this comment -->
                <dependency>
                  <groupId>jakarta.servlet</groupId>
                  <artifactId>jakarta.servlet-api</artifactId>
                  <version>6.0.0</version>
                  <scope>compile</scope>
                </dependency>
                <dependency>
                  <groupId>jakarta.annotation</groupId>
                  <artifactId>jakarta.annotation-api</artifactId>
                  <version>2.1.1</version>
                  <exclusions>
                    <exclusion>
                      <groupId>x</groupId>
                      <artifactId>y</artifactId>
                    </exclusion>
                  </exclusions>
                </dependency>
                <dependency>
                  <groupId>org.springframework</groupId>
                  <artifactId>spring-webmvc</artifactId>
                  <version>6.2.19</version>
                </dependency>
              </dependencies>
            </project>
            """;

    @Test
    void setsScopeOnRealDependenciesOnlyAndKeepsFormatting() {
        PomEditor.Result r = PomEditor.setDependencyScope(POM, (g, a) -> g.startsWith("jakarta."), "provided");
        assertThat(r.changes()).isEqualTo(2);
        assertThat(r.content())
                .contains("<!-- servlet API: keep this comment -->")
                .contains("      <version>6.0.0</version>\n      <scope>provided</scope>\n    </dependency>")
                .contains("      <version>2.1.1</version>\n      <scope>provided</scope>\n      <exclusions>")
                .doesNotContain("<scope>compile</scope>");
        // The managed declaration and unrelated dependencies are untouched.
        assertThat(r.content().lines().filter(l -> l.contains("<scope>")).count()).isEqualTo(2);
        assertThat(PomEditor.setDependencyScope(r.content(), (g, a) -> g.startsWith("jakarta."), "provided").changes()).isZero();
    }

    @Test
    void addsAMissingPluginWithTheFilesIndentation() {
        PomEditor.Result r = PomEditor.setPluginVersion(POM, "org.apache.maven.plugins", "maven-war-plugin", "3.4.0");
        assertThat(r.changes()).isEqualTo(1);
        assertThat(r.content()).contains("""
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-war-plugin</artifactId>
                        <version>3.4.0</version>
                      </plugin>
                    </plugins>
                  </build>
                </project>""");
    }

    @Test
    void updatesAnExistingPluginVersionButNotItsDependencies() {
        String pom = """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <build>
                        <plugins>
                            <plugin>
                                <artifactId>maven-war-plugin</artifactId>
                                <version>2.2</version>
                                <dependencies>
                                    <dependency><artifactId>x</artifactId><version>1.0</version></dependency>
                                </dependencies>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """;
        PomEditor.Result r = PomEditor.setPluginVersion(pom, "org.apache.maven.plugins", "maven-war-plugin", "3.4.0");
        assertThat(r.changes()).isEqualTo(1);
        assertThat(r.content()).contains("<version>3.4.0</version>").contains("<version>1.0</version>")
                .doesNotContain("<version>2.2</version>");
    }

    @Test
    void addsAPropertyNextToTheExistingOnes() {
        PomEditor.Result r = PomEditor.setProperty(POM, "maven.compiler.target", "${maven.compiler.source}");
        assertThat(r.content()).contains("    <maven.compiler.source>21</maven.compiler.source>\n"
                + "    <maven.compiler.target>${maven.compiler.source}</maven.compiler.target>\n"
                + "  </properties>");
        assertThat(PomEditor.setProperty(r.content(), "maven.compiler.target", "x").changes()).isZero();
    }

    @Test
    void addsADependencyNextToItsSiblingsOnlyOnce() {
        PomEditor.Result r = PomEditor.addDependency(POM, "jakarta.el", "jakarta.el-api", "5.0.1", "provided");
        assertThat(r.changes()).isEqualTo(1);
        assertThat(r.content()).contains("      <version>6.2.19</version>\n    </dependency>\n"
                + "    <dependency>\n      <groupId>jakarta.el</groupId>\n      <artifactId>jakarta.el-api</artifactId>\n"
                + "      <version>5.0.1</version>\n      <scope>provided</scope>\n    </dependency>\n  </dependencies>");
        // Already declared (also when only the dependencyManagement copy differs): no change.
        assertThat(PomEditor.addDependency(r.content(), "jakarta.el", "jakarta.el-api", "5.0.1", "provided").changes()).isZero();
        assertThat(PomEditor.addDependency(POM, "jakarta.servlet", "jakarta.servlet-api", "6.0.0", null).changes()).isZero();
    }

    @Test
    void createsTheDependenciesSectionWhenMissing() {
        String pom = "<project>\n    <modelVersion>4.0.0</modelVersion>\n    <artifactId>a</artifactId>\n\n"
                + "    <build>\n    </build>\n</project>\n";
        PomEditor.Result r = PomEditor.addDependency(pom, "g", "a", "1", "compile");
        assertThat(r.content()).isEqualTo("<project>\n    <modelVersion>4.0.0</modelVersion>\n    <artifactId>a</artifactId>\n\n"
                + "    <dependencies>\n        <dependency>\n            <groupId>g</groupId>\n            <artifactId>a</artifactId>\n"
                + "            <version>1</version>\n        </dependency>\n    </dependencies>\n\n    <build>\n    </build>\n</project>\n");
    }
}
