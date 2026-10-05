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

/** Individual bundled rules against small projects written for each case. */
class JavaRulesTest {

    private static final PluginRegistry REGISTRY = PluginRegistry.load();
    private static final Playbook PLAYBOOK = REGISTRY.playbook("java8-to-21-jakarta-ee10");

    private static List<Finding> check(Path root, String ruleId) throws Exception {
        Rule rule = PLAYBOOK.rules().stream().filter(r -> r.id().equals(ruleId)).findFirst().orElseThrow();
        return new Analyzer(REGISTRY).check(new JavaPlugin().model(root), PLAYBOOK, List.of(rule)).findings();
    }

    private static void pom(Path dir, String dependencies) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId>"
                + "<artifactId>" + dir.getFileName() + "</artifactId><version>1</version><dependencies>" + dependencies
                + "</dependencies></project>");
    }

    private static String dep(String ga, String scope) {
        String[] p = ga.split(":");
        return "<dependency><groupId>" + p[0] + "</groupId><artifactId>" + p[1] + "</artifactId><version>1</version>"
                + (scope == null ? "" : "<scope>" + scope + "</scope>") + "</dependency>";
    }

    @Test
    void jaxbApiWithoutAnImplementationIsReported(@TempDir Path root) throws Exception {
        pom(root.resolve("bare"), dep("jakarta.xml.bind:jakarta.xml.bind-api", null));
        pom(root.resolve("withRuntime"), dep("jakarta.xml.bind:jakarta.xml.bind-api", null)
                + dep("org.glassfish.jaxb:jaxb-runtime", "runtime"));
        pom(root.resolve("serverProvided"), dep("jakarta.xml.bind:jakarta.xml.bind-api", "provided"));
        pom(root.resolve("fullPlatform"), dep("jakarta.xml.bind:jakarta.xml.bind-api", null)
                + dep("jakarta.platform:jakarta.jakartaee-api", "provided"));

        assertThat(check(root, "guard-jaxb-implementation")).extracting(Finding::file).containsExactly("bare/pom.xml");
    }

    @Test
    void nashornLookupsAreReportedButOtherEnginesAreNot(@TempDir Path root) throws Exception {
        pom(root, "");
        Path src = Files.createDirectories(root.resolve("src/main/java/x"));
        Files.writeString(src.resolve("Rules.java"), """
                class Rules {
                    Object a = new javax.script.ScriptEngineManager().getEngineByName("nashorn");
                    Object b = new javax.script.ScriptEngineManager().getEngineByName( "JavaScript" );
                    Object c = new javax.script.ScriptEngineManager().getEngineByName("groovy");
                }
                """);
        assertThat(check(root, "nashorn-script-engine")).extracting(Finding::line).containsExactly(2, 3);
    }
}
