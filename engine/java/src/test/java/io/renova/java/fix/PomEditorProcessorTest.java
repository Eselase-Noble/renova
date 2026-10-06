package io.renova.java.fix;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PomEditorProcessorTest {

    private static final String POM = """
            <project>
              <build>
                <plugins>
                  <plugin>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <configuration>
                      <annotationProcessorPaths combine.children="append">
                        <path>
                          <groupId>io.micronaut</groupId>
                          <artifactId>micronaut-http-validation</artifactId>
                          <version>${micronaut.version}</version>
                        </path>
                      </annotationProcessorPaths>
                    </configuration>
                  </plugin>
                </plugins>
              </build>
            </project>
            """;

    @Test
    void addsAProcessorBesideTheOthers() {
        PomEditor.Result r = PomEditor.addAnnotationProcessorPath(POM, "io.micronaut.validation", "micronaut-validation-processor",
                "${micronaut.validation.version}");
        assertThat(r.changes()).isEqualTo(1);
        assertThat(r.content()).contains("""
                            </path>
                            <path>
                              <groupId>io.micronaut.validation</groupId>
                              <artifactId>micronaut-validation-processor</artifactId>
                              <version>${micronaut.validation.version}</version>
                            </path>
                          </annotationProcessorPaths>
                """);
        assertThat(PomEditor.addAnnotationProcessorPath(r.content(), "io.micronaut.validation", "micronaut-validation-processor", null).changes())
                .isZero();
    }

    @Test
    void aPomWithoutTheListIsLeftAlone() {
        String pom = "<project><build/></project>";
        assertThat(PomEditor.addAnnotationProcessorPath(pom, "g", "a", "1").content()).isEqualTo(pom);
    }

    @Test
    void aDependencyTheParentManagesIsAddedWithoutAVersion() {
        String pom = "<project>\n  <dependencies>\n    <dependency>\n      <groupId>g</groupId>\n      <artifactId>a</artifactId>\n    </dependency>\n  </dependencies>\n</project>\n";
        assertThat(PomEditor.addDependency(pom, "io.micronaut.validation", "micronaut-validation", null, null).content())
                .contains("<artifactId>micronaut-validation</artifactId>\n    </dependency>").doesNotContain("<version>null");
    }
}
