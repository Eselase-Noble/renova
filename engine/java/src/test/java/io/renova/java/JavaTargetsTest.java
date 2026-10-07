package io.renova.java;

import io.renova.core.engine.Analyzer;
import io.renova.core.engine.PluginRegistry;
import io.renova.core.model.Finding;
import io.renova.core.playbook.Playbook;
import io.renova.core.playbook.Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The bundled migration paths: which one a project is offered, and what each is made of. */
class JavaTargetsTest {

    private static final PluginRegistry REGISTRY = PluginRegistry.load();

    private static Path project(Path dir, String pomBody) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion>" + pomBody + "</project>");
        return dir;
    }

    private static String bootParent(String version) {
        return "<parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>"
                + version + "</version></parent><artifactId>app</artifactId>"
                + "<dependencies><dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId>"
                + "</dependency></dependencies>";
    }

    @Test
    void eachProjectIsOfferedThePathThatFitsIt(@TempDir Path root) throws Exception {
        Path plain = project(root.resolve("plain"), "<groupId>g</groupId><artifactId>lib</artifactId><version>1</version>"
                + "<properties><maven.compiler.release>17</maven.compiler.release></properties>");
        Path webapp = project(root.resolve("webapp"), "<groupId>g</groupId><artifactId>shop</artifactId><version>1</version>"
                + "<packaging>war</packaging><dependencies><dependency><groupId>javax.servlet</groupId>"
                + "<artifactId>javax.servlet-api</artifactId><version>3.1.0</version></dependency></dependencies>");
        Path boot2 = project(root.resolve("boot2"), bootParent("2.7.18"));
        Path boot3 = project(root.resolve("boot3"), bootParent("3.3.4"));

        assertThat(REGISTRY.defaultPlaybook(plain).id()).isEqualTo("java-to-21");
        assertThat(REGISTRY.defaultPlaybook(webapp).id()).isEqualTo("java8-to-21-jakarta-ee10");
        assertThat(REGISTRY.defaultPlaybook(boot2).id()).isEqualTo("spring-boot-3");
        assertThat(REGISTRY.defaultPlaybook(boot3).id()).isEqualTo("spring-boot-4");
        Path micronaut = project(root.resolve("micronaut"), "<parent><groupId>io.micronaut</groupId><artifactId>micronaut-parent"
                + "</artifactId><version>3.10.4</version></parent><artifactId>svc</artifactId>");
        Path quarkus = project(root.resolve("quarkus"), "<groupId>g</groupId><artifactId>svc</artifactId><version>1</version>"
                + "<build><plugins><plugin><groupId>io.quarkus.platform</groupId><artifactId>quarkus-maven-plugin</artifactId>"
                + "<version>2.16.12.Final</version></plugin></plugins></build>");
        assertThat(REGISTRY.defaultPlaybook(micronaut).id()).isEqualTo("micronaut-4");
        assertThat(REGISTRY.defaultPlaybook(quarkus).id()).isEqualTo("quarkus-3");
        // Struts 7 continues Struts 2. Struts 1 is another framework with the same groupId: no add-on for it.
        String strutsWar = "<groupId>g</groupId><artifactId>desk</artifactId><version>1</version><packaging>war</packaging>"
                + "<dependencies><dependency><groupId>org.apache.struts</groupId><artifactId>%s</artifactId><version>%s</version>"
                + "</dependency></dependencies>";
        Path struts2 = project(root.resolve("struts2"), strutsWar.formatted("struts2-core", "2.5.33"));
        Path struts1 = project(root.resolve("struts1"), strutsWar.formatted("struts-core", "1.3.10"));
        assertThat(REGISTRY.defaultPlaybook(struts2).id()).isEqualTo("java8-to-21-jakarta-ee10+struts7");
        assertThat(REGISTRY.defaultPlaybook(struts1).id()).isEqualTo("java8-to-21-jakarta-ee10");
        // Every Java path stays available for whoever wants a different target.
        assertThat(REGISTRY.playbooksFor(plain)).extracting(Playbook::id)
                .contains("java-to-17", "java-to-21", "java-to-25", "spring-boot-3", "spring-boot-4", "java8-to-21-jakarta-ee10",
                        "java-to-21-jakarta-ee11-spring7");
    }

    @Test
    void javaOnlyPathsRaiseTheLevelAndLeaveFrameworksAlone() {
        for (String version : List.of("17", "21", "25")) {
            Playbook p = REGISTRY.playbook("java-to-" + version);
            assertThat(p.targets()).containsExactly(java.util.Map.entry("java", version));
            Rule level = p.rules().getFirst();
            assertThat(level.id()).isEqualTo("java-level");
            assertThat(level.fix().recipes()).containsExactly("org.openrewrite.java.migrate.UpgradeToJava" + version);
            assertThat(p.rules()).extracting(Rule::id).contains("sun-misc-base64", "nashorn-script-engine", "guard-compiler-target")
                    .doesNotContain("javax-ee-imports", "spring-framework-6");
        }
    }

    @Test
    void springBootPathsLookAtTheParentVersion(@TempDir Path root) throws Exception {
        project(root.resolve("boot2"), bootParent("2.7.18"));
        project(root.resolve("boot3"), bootParent("3.3.4"));
        project(root.resolve("boot4"), bootParent("4.1.1"));
        project(root.resolve("other"), "<parent><groupId>com.acme</groupId><artifactId>acme-parent</artifactId><version>1</version>"
                + "</parent><artifactId>svc</artifactId>");

        assertThat(parents(root, "spring-boot-3", "spring-boot-3")).containsExactly("boot2/pom.xml");
        assertThat(parents(root, "spring-boot-4", "spring-boot-4")).containsExactly("boot2/pom.xml", "boot3/pom.xml");
        // Coming from Spring Boot 2, the path to 4 includes everything the path to 3 does.
        assertThat(REGISTRY.playbook("spring-boot-4").rules()).extracting(Rule::id)
                .contains("javax-ee-imports", "spring-security-configurer-adapter", "spring-boot-4-test-doubles");
    }

    @Test
    void aParentTheRecipeLeftBehindIsBroughtToTheTarget(@TempDir Path root) throws Exception {
        // What the recipe leaves when a release lookup fails part-way: Spring Boot 3.3 under code already moved on.
        project(root.resolve("stalled"), bootParent("3.3.13"));
        project(root.resolve("done"), bootParent("3.5.7"));
        assertThat(parents(root, "spring-boot-3", "guard-spring-boot-parent")).containsExactly("stalled/pom.xml");
        assertThat(parents(root, "spring-boot-4", "guard-spring-boot-parent")).containsExactly("done/pom.xml", "stalled/pom.xml");

        String pom = Files.readString(root.resolve("stalled/pom.xml"));
        String fixed = io.renova.java.fix.MavenVerifierAccess.setParentVersion(pom, "3.5.7");
        assertThat(fixed).contains("<artifactId>spring-boot-starter-parent</artifactId><version>3.5.7</version>")
                .doesNotContain("3.3.13");
        assertThat(io.renova.java.fix.MavenVerifierAccess.setParentVersion("<project><version>1</version></project>", "9"))
                .isEqualTo("<project><version>1</version></project>");
    }

    @Test
    void springBootInAGradleBuildIsReadFromItsPlugin(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("groovy"));
        Files.writeString(root.resolve("groovy/build.gradle"), """
                plugins {
                    id 'java'
                    id 'org.springframework.boot' version '2.7.18'
                }
                """);
        Files.createDirectories(root.resolve("kotlin"));
        Files.writeString(root.resolve("kotlin/build.gradle.kts"), """
                plugins {
                    java
                    id("org.springframework.boot") version "3.3.4"
                }
                """);
        Files.writeString(root.resolve("groovy/build.gradle"), Files.readString(root.resolve("groovy/build.gradle")) + """
                dependencies {
                    implementation 'org.springframework.boot:spring-boot-starter-web'
                    runtimeOnly "com.h2database:h2:2.1.214"
                }
                """);
        // The Spring Boot plugin manages the starters' versions, so they are written without one.
        assertThat(parents(root, "spring-boot-3", "spring-boot-trailing-slash")).containsExactly("groovy/build.gradle");
        assertThat(parents(root, "spring-boot-3", "spring-boot-gradle-plugin")).containsExactly("groovy/build.gradle");
        assertThat(parents(root, "spring-boot-4", "spring-boot-gradle-plugin")).containsExactly("groovy/build.gradle", "kotlin/build.gradle.kts");
        assertThat(REGISTRY.defaultPlaybook(root.resolve("groovy")).id()).isEqualTo("spring-boot-3");
        assertThat(REGISTRY.defaultPlaybook(root.resolve("kotlin")).id()).isEqualTo("spring-boot-4");
    }

    @Test
    void aFolderOfProjectsIsAssessedAndRankedEasiestFirst(@TempDir Path root) throws Exception {
        project(root.resolve("libs/plain"), "<groupId>g</groupId><artifactId>lib</artifactId><version>1</version>"
                + "<properties><maven.compiler.release>17</maven.compiler.release></properties>");
        Path webapp = project(root.resolve("apps/webapp"), "<groupId>g</groupId><artifactId>shop</artifactId><version>1</version>"
                + "<packaging>war</packaging><modules><module>core</module></modules><dependencies><dependency>"
                + "<groupId>javax.servlet</groupId><artifactId>javax.servlet-api</artifactId><version>3.1.0</version></dependency>"
                + "<dependency><groupId>org.springframework</groupId><artifactId>spring-webmvc</artifactId><version>4.3.30.RELEASE</version>"
                + "</dependency></dependencies>");
        // A module of the web application: part of it, not a project of its own.
        project(webapp.resolve("core"), "<groupId>g</groupId><artifactId>core</artifactId><version>1</version>");
        project(root.resolve("apps/boot2"), bootParent("2.7.18"));
        Files.createDirectories(root.resolve("docs/notes"));
        Files.createDirectories(root.resolve("apps/webapp/target/classes"));

        io.renova.core.engine.Portfolio.Result result = new io.renova.core.engine.Portfolio(REGISTRY).assess(root, line -> { });

        assertThat(result.entries()).extracting(io.renova.core.engine.Portfolio.Entry::path)
                .containsExactlyInAnyOrder("libs/plain", "apps/webapp", "apps/boot2");
        assertThat(result.entries()).filteredOn(e -> e.path().equals("apps/webapp")).singleElement().satisfies(e -> {
            assertThat(e.playbook()).isEqualTo("java8-to-21-jakarta-ee10");
            // Spring MVC URL matching is left to a person.
            assertThat(e.manualSteps()).isGreaterThan(0);
            assertThat(e.fullyAutomatic()).isFalse();
        });
        // The plain library needs no decision from anyone, so it comes first.
        assertThat(result.entries().getFirst().path()).isEqualTo("libs/plain");
        assertThat(result.entries().getFirst().fullyAutomatic()).isTrue();
        assertThat(result.fullyAutomatic()).isGreaterThanOrEqualTo(1);
        assertThat(result.findings()).isEqualTo(result.entries().stream().mapToInt(io.renova.core.engine.Portfolio.Entry::findings).sum());

        assertThat(io.renova.core.engine.Portfolio.markdown(result)).contains("| Projects | 3 |", "| libs/plain | Java → 21 |");
        assertThat(io.renova.core.engine.Portfolio.csv(result)).startsWith("project,path,").contains("\"apps/boot2\"", "\"spring-boot-3\"");
    }

    private static List<String> parents(Path root, String playbookId, String ruleId) throws Exception {
        Playbook playbook = REGISTRY.playbook(playbookId);
        Rule rule = playbook.rules().stream().filter(r -> r.id().equals(ruleId)).findFirst().orElseThrow();
        return new Analyzer(REGISTRY).check(new JavaPlugin().model(root), playbook, List.of(rule)).findings().stream()
                .map(Finding::file).sorted().toList();
    }
}
