package io.renova.java;

import io.renova.core.ai.AiSettings;
import io.renova.core.engine.AnalysisResult;
import io.renova.core.engine.Analyzer;
import io.renova.core.engine.MigrationOptions;
import io.renova.core.engine.MigrationOutcome;
import io.renova.core.engine.Migrator;
import io.renova.core.engine.Planner;
import io.renova.core.engine.PluginRegistry;
import io.renova.core.engine.StageResult;
import io.renova.core.playbook.Playbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs a migration with recipes and AI skipped, on a copy of the fixture whose pom looks like a
 * typical half-migrated build, so only the guard rules change it. No Maven or network involved.
 */
class GuardRulesTest {

    @Test
    void guardsFixScopePluginCompilerTargetAndMissingDependencyBeforeVerification(@TempDir Path tmp) throws Exception {
        Path project = copyFixture(tmp.resolve("project"));
        Path pom = project.resolve("pom.xml");
        // What the Jakarta recipe produced in the demo: the API moved to compile scope.
        Files.writeString(pom, Files.readString(pom)
                .replace("<groupId>javax.servlet</groupId>", "<groupId>jakarta.servlet</groupId>")
                .replace("<artifactId>javax.servlet-api</artifactId>", "<artifactId>jakarta.servlet-api</artifactId>")
                .replace("<scope>provided</scope>", "<scope>compile</scope>"));
        // What the Jakarta recipe produced in the code: javax.annotation (from the JDK) renamed.
        Path controller = project.resolve("src/main/java/com/acme/web/OrderController.java");
        Files.writeString(controller, Files.readString(controller).replace("javax.annotation.", "jakarta.annotation."));

        PluginRegistry registry = PluginRegistry.load();
        Playbook playbook = registry.defaultPlaybook(project);
        AnalysisResult analysis = new Analyzer(registry).analyze(project, playbook);
        assertThat(analysis.findings()).noneMatch(f -> f.ruleId().startsWith("guard-"));

        Path out = tmp.resolve("out");
        MigrationOutcome outcome = new Migrator(registry, m -> { }).migrate(analysis, new Planner().plan(analysis),
                new MigrationOptions(out, AiSettings.NONE, 0, false, Map.of(), List.of("recipe", "replace", "ai")));

        String migrated = Files.readString(out.resolve("pom.xml"));
        assertThat(migrated)
                .contains("<artifactId>jakarta.servlet-api</artifactId>\n            <version>3.1.0</version>\n            <scope>provided</scope>")
                .contains("<artifactId>maven-war-plugin</artifactId>\n                <version>3.4.0</version>")
                .contains("<maven.compiler.target>${maven.compiler.source}</maven.compiler.target>")
                .contains("        <dependency>\n"
                        + "            <groupId>jakarta.annotation</groupId>\n"
                        + "            <artifactId>jakarta.annotation-api</artifactId>\n"
                        + "            <version>2.1.1</version>\n"
                        + "            <scope>provided</scope>\n"
                        + "        </dependency>\n"
                        + "    </dependencies>");
        // The JBoss variant gets the same build fixes (its servlet API was never touched, so stays provided).
        assertThat(Files.readString(out.resolve("pom.jboss.xml")))
                .contains("maven-war-plugin", "<maven.compiler.target>", "jakarta.annotation-api");
        assertThat(Files.readString(pom)).doesNotContain("maven-war-plugin");

        assertThat(outcome.stages()).extracting(StageResult::stage).contains("guard", "guard maven");
        assertThat(outcome.stages()).filteredOn(s -> s.stage().equals("guard")).singleElement()
                // The fifth is the message-less Assert call the skipped recipe would have converted.
                .satisfies(s -> assertThat(s.summary()).startsWith("5 of "
                        + playbook.rules().stream().filter(r -> r.guard()).count() + " guard rule(s) found problems"));
    }

    @Test
    void guardsRunAgainWhenOneFixCreatesWorkForAnother(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>g</groupId>
                    <artifactId>xml</artifactId>
                    <version>1</version>
                    <properties>
                        <maven.compiler.release>21</maven.compiler.release>
                    </properties>
                    <dependencies>
                    </dependencies>
                </project>
                """);
        Path src = Files.createDirectories(project.resolve("src/main/java/x"));
        Files.writeString(src.resolve("Feed.java"), "package x;\nimport jakarta.xml.bind.JAXBContext;\nclass Feed {}\n");

        PluginRegistry registry = PluginRegistry.load();
        Playbook playbook = registry.defaultPlaybook(project);
        AnalysisResult analysis = new Analyzer(registry).analyze(project, playbook);
        Path out = tmp.resolve("out");
        MigrationOutcome outcome = new Migrator(registry, m -> { }).migrate(analysis, new Planner().plan(analysis),
                new MigrationOptions(out, AiSettings.NONE, 0, false, Map.of(), List.of()));

        // Pass 1 declares the API the code imports; pass 2 sees it and adds the implementation.
        assertThat(Files.readString(out.resolve("pom.xml")))
                .contains("<artifactId>jakarta.xml.bind-api</artifactId>")
                .contains("<artifactId>jaxb-runtime</artifactId>\n            <version>4.0.5</version>\n            <scope>runtime</scope>");
        assertThat(outcome.stages()).extracting(StageResult::stage).contains("guard", "guard maven", "guard pass 2", "guard pass 2 maven");
    }

    @Test
    void guardsRepairRecipeLeftoversInAMultiModuleBuild(@TempDir Path tmp) throws Exception {
        // What the recipes left in inventory-platform: the parent manages the -servlet6 artifact, the
        // module renamed its dependency to -servlet5 without a version, the servlet API was added a
        // second time, and one Assert call kept its removed one-argument form.
        Path project = Files.createDirectories(tmp.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.acme</groupId>
                    <artifactId>platform</artifactId>
                    <version>1</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>web</module>
                    </modules>
                    <properties>
                        <maven.compiler.release>21</maven.compiler.release>
                    </properties>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>jakarta.servlet</groupId>
                                <artifactId>jakarta.servlet-api</artifactId>
                                <version>6.0.0</version>
                                <scope>provided</scope>
                            </dependency>
                            <dependency>
                                <groupId>org.apache.commons</groupId>
                                <artifactId>commons-fileupload2-jakarta-servlet6</artifactId>
                                <version>2.0.0-M4</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);
        Path web = Files.createDirectories(project.resolve("web"));
        Files.writeString(web.resolve("pom.xml"), """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.acme</groupId>
                        <artifactId>platform</artifactId>
                        <version>1</version>
                    </parent>
                    <artifactId>web</artifactId>
                    <packaging>jar</packaging>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.servlet</groupId>
                            <artifactId>jakarta.servlet-api</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>jakarta.servlet</groupId>
                            <artifactId>jakarta.servlet-api</artifactId>
                            <version>5.0.0</version>
                        </dependency>
                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-fileupload2-jakarta-servlet5</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);
        Path src = Files.createDirectories(web.resolve("src/main/java/com/acme/web"));
        Files.writeString(src.resolve("ItemController.java"), """
                package com.acme.web;

                import org.springframework.util.Assert;

                class ItemController {
                    void show(Object item, Request request) {
                        Assert.notNull(item);
                        Assert.isTrue(request.isUserInRole("STOCK"));
                        Assert.hasText(request.name(), "name required");
                    }
                }
                """);
        // A project's own Assert class with the same method names must not change.
        String ownAssert = "package com.acme.web;\nimport com.acme.util.Assert;\nclass Other { void x(Object o) { Assert.notNull(o); } }\n";
        Files.writeString(src.resolve("Other.java"), ownAssert);

        PluginRegistry registry = PluginRegistry.load();
        Playbook playbook = registry.defaultPlaybook(project);
        AnalysisResult analysis = new Analyzer(registry).analyze(project, playbook);
        Path out = tmp.resolve("out");
        new Migrator(registry, m -> { }).migrate(analysis, new Planner().plan(analysis),
                new MigrationOptions(out, AiSettings.NONE, 0, false, Map.of(), List.of("recipe", "ai")));

        assertThat(Files.readString(out.resolve("web/pom.xml"))).isEqualTo("""
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.acme</groupId>
                        <artifactId>platform</artifactId>
                        <version>1</version>
                    </parent>
                    <artifactId>web</artifactId>
                    <packaging>jar</packaging>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.servlet</groupId>
                            <artifactId>jakarta.servlet-api</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-fileupload2-jakarta-servlet5</artifactId>
                            <version>2.0.0-M4</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        assertThat(Files.readString(out.resolve("web/src/main/java/com/acme/web/ItemController.java")))
                .contains("Assert.notNull(item, \"[Assertion failed] - this argument is required; it must not be null\");")
                .contains("Assert.isTrue(request.isUserInRole(\"STOCK\"), \"[Assertion failed] - this expression must be true\");")
                .contains("Assert.hasText(request.name(), \"name required\");");
        assertThat(Files.readString(out.resolve("web/src/main/java/com/acme/web/Other.java"))).isEqualTo(ownAssert);
    }

    @Test
    void noVersionIsGuessedWhenTheParentIsOutsideTheProject(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>3.5.0</version>
                    </parent>
                    <artifactId>app</artifactId>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.apache.commons</groupId>
                                <artifactId>commons-fileupload2-jakarta-servlet6</artifactId>
                                <version>2.0.0-M4</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-fileupload2-jakarta-servlet5</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);
        PluginRegistry registry = PluginRegistry.load();
        Playbook playbook = registry.defaultPlaybook(project);
        AnalysisResult check = new Analyzer(registry).check(new Analyzer(registry).analyze(project, playbook).project(), playbook,
                playbook.rules().stream().filter(r -> r.id().equals("guard-dependency-version")).toList());
        assertThat(check.findings()).isEmpty();
    }

    @Test
    void javaxJstlIsReplacedWithJakartaJstlAndItsImplementation(@TempDir Path tmp) throws Exception {
        // What behavioural verification found in inventory-platform: JSTL 1.2 still bundled, so every JSP with a
        // tag failed on Tomcat 10.1 with NoClassDefFoundError although the build passed.
        Path project = Files.createDirectories(tmp.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>g</groupId>
                    <artifactId>parent</artifactId>
                    <version>1</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>web</module>
                    </modules>
                    <properties>
                        <maven.compiler.release>21</maven.compiler.release>
                    </properties>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>javax.servlet</groupId>
                                <artifactId>jstl</artifactId>
                                <version>1.2</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);
        Files.createDirectories(project.resolve("web"));
        Files.writeString(project.resolve("web/pom.xml"), """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>g</groupId>
                        <artifactId>parent</artifactId>
                        <version>1</version>
                    </parent>
                    <artifactId>web</artifactId>
                    <packaging>jar</packaging>
                    <dependencies>
                        <dependency>
                            <groupId>javax.servlet</groupId>
                            <artifactId>jstl</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);

        PluginRegistry registry = PluginRegistry.load();
        Playbook playbook = registry.defaultPlaybook(project);
        AnalysisResult analysis = new Analyzer(registry).analyze(project, playbook);
        Path out = tmp.resolve("out");
        new Migrator(registry, m -> { }).migrate(analysis, new Planner().plan(analysis),
                new MigrationOptions(out, AiSettings.NONE, 0, false, Map.of(), List.of("recipe", "ai")));

        assertThat(Files.readString(out.resolve("web/pom.xml"))).contains("""
                        <dependency>
                            <groupId>jakarta.servlet.jsp.jstl</groupId>
                            <artifactId>jakarta.servlet.jsp.jstl-api</artifactId>
                            <version>3.0.0</version>
                        </dependency>
                        <dependency>
                            <groupId>org.glassfish.web</groupId>
                            <artifactId>jakarta.servlet.jsp.jstl</artifactId>
                            <version>3.0.1</version>
                        </dependency>
                """).doesNotContain("<artifactId>jstl</artifactId>");
        // The parent's managed entry is renamed too, keeping its version element.
        assertThat(Files.readString(out.resolve("pom.xml")))
                .contains("<artifactId>jakarta.servlet.jsp.jstl-api</artifactId>\n                <version>3.0.0</version>");
    }

    private static Path copyFixture(Path target) throws Exception {
        Path source = Path.of(GuardRulesTest.class.getResource("/fixtures/legacy-webapp").toURI());
        try (Stream<Path> files = Files.walk(source)) {
            for (Path p : files.sorted(Comparator.naturalOrder()).toList()) {
                Path dest = target.resolve(source.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(p, dest);
                }
            }
        }
        return target;
    }
}
