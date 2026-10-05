package io.renova.java;

import io.renova.core.engine.AnalysisResult;
import io.renova.core.engine.Analyzer;
import io.renova.core.engine.PluginRegistry;
import io.renova.core.model.Finding;
import io.renova.core.playbook.Playbook;
import io.renova.core.playbook.PlaybookLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImportDependencyDetectorTest {

    private static final Playbook PLAYBOOK = PlaybookLoader.load(new ByteArrayInputStream("""
            id: t
            ecosystem: java
            rules:
              - id: declared
                phase: guard
                detect:
                  type: importWithoutDependency
                  providedByAny: ["jakarta.platform:*"]
                  provides:
                    - { package: jakarta.servlet.jsp, dependency: "jakarta.servlet.jsp:jakarta.servlet.jsp-api:3.1.1", scope: provided }
                    - { package: jakarta.servlet, dependency: "jakarta.servlet:jakarta.servlet-api:6.0.0", scope: provided }
                    - { package: jakarta.annotation, dependency: "jakarta.annotation:jakarta.annotation-api:2.1.1", scope: provided }
                fix: { strategy: maven, params: { action: addDependency } }
            """.getBytes(StandardCharsets.UTF_8)), "test");

    private static String pom(String artifactId, String modules, String dependencies) {
        return """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>g</groupId>
                    <artifactId>%s</artifactId>
                    <version>1</version>
                    %s
                    <dependencies>%s</dependencies>
                </project>
                """.formatted(artifactId, modules, dependencies);
    }

    private static String dep(String ga, String extra) {
        String[] p = ga.split(":");
        return "<dependency><groupId>" + p[0] + "</groupId><artifactId>" + p[1] + "</artifactId>" + extra + "</dependency>";
    }

    private static void source(Path module, String name, String... imports) throws Exception {
        Path dir = Files.createDirectories(module.resolve("src/main/java/x"));
        StringBuilder src = new StringBuilder("package x;\n");
        for (String i : imports) {
            src.append("import ").append(i).append(";\n");
        }
        Files.writeString(dir.resolve(name + ".java"), src + "class " + name + " {}\n");
    }

    private static AnalysisResult check(Path root) throws Exception {
        PluginRegistry registry = PluginRegistry.load();
        return new Analyzer(registry).check(new JavaPlugin().model(root), PLAYBOOK, PLAYBOOK.rules());
    }

    @Test
    void reportsEachUndeclaredPackageOncePerBuildFileWithCoordinates(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), pom("app", "", dep("jakarta.servlet:jakarta.servlet-api", "")));
        source(root, "A", "jakarta.servlet.http.HttpServletRequest", "jakarta.servlet.jsp.PageContext", "jakarta.annotation.Resource");
        source(root, "B", "jakarta.annotation.PostConstruct");

        AnalysisResult result = check(root);

        // jakarta.servlet is declared; jsp-api is a different artifact even though the package is nested.
        assertThat(result.findings()).extracting(f -> f.data().get("artifactId"))
                .containsExactlyInAnyOrder("jakarta.servlet.jsp-api", "jakarta.annotation-api");
        Finding annotation = result.findings().stream()
                .filter(f -> f.data().get("artifactId").equals("jakarta.annotation-api")).findFirst().orElseThrow();
        assertThat(annotation.file()).isEqualTo("pom.xml");
        assertThat(annotation.data()).containsAllEntriesOf(Map.of("groupId", "jakarta.annotation", "version", "2.1.1",
                "scope", "provided"));
        assertThat(annotation.evidence()).contains("imported in 2 file(s)");
    }

    @Test
    void parentDeclarationsAndUmbrellaApisCountButManagedOnesDoNot(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), pom("parent", "<packaging>pom</packaging><modules><module>a</module>"
                + "<module>b</module><module>c</module></modules>", dep("jakarta.annotation:jakarta.annotation-api", "")));
        Path a = Files.createDirectories(root.resolve("a"));
        Path b = Files.createDirectories(root.resolve("b"));
        Path c = Files.createDirectories(root.resolve("c"));
        Files.writeString(a.resolve("pom.xml"), pom("a", "", ""));
        Files.writeString(b.resolve("pom.xml"), pom("b", "", dep("jakarta.platform:jakarta.jakartaee-api", "")));
        Files.writeString(c.resolve("pom.xml"), pom("c", "<dependencyManagement><dependencies>"
                + dep("jakarta.servlet:jakarta.servlet-api", "<version>6.0.0</version>") + "</dependencies></dependencyManagement>", ""));
        source(a, "A", "jakarta.annotation.Resource");                      // inherited from the parent
        source(b, "B", "jakarta.servlet.http.HttpServlet");                 // covered by the umbrella API
        source(c, "C", "jakarta.servlet.http.HttpServlet");                 // only managed: still missing

        assertThat(check(root).findings()).singleElement().satisfies(f -> {
            assertThat(f.file()).isEqualTo("c/pom.xml");
            assertThat(f.data().get("artifactId")).isEqualTo("jakarta.servlet-api");
        });
    }

    @Test
    void moduleBuildFilesRelateToTheirParentBuildFile(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), pom("parent", "<packaging>pom</packaging><modules><module>web</module></modules>", ""));
        Path web = Files.createDirectories(root.resolve("web"));
        Files.writeString(web.resolve("pom.xml"), pom("web", "", ""));
        JavaPlugin plugin = new JavaPlugin();
        var model = plugin.model(root);

        assertThat(plugin.relatedFiles(model, "web/pom.xml")).singleElement().satisfies(r -> {
            assertThat(r.path()).isEqualTo("pom.xml");
            assertThat(r.editable()).isTrue();
        });
        assertThat(plugin.relatedFiles(model, "web/src/main/java/x/A.java")).singleElement()
                .satisfies(r -> assertThat(r.path()).isEqualTo("web/pom.xml"));
        assertThat(plugin.relatedFiles(model, "pom.xml")).isEmpty();
    }
}
