package io.renova.java;

import io.renova.core.engine.AnalysisResult;
import io.renova.core.engine.Analyzer;
import io.renova.core.engine.MigrationPlan;
import io.renova.core.engine.Planner;
import io.renova.core.engine.PluginRegistry;
import io.renova.core.model.Category;
import io.renova.core.model.Finding;
import io.renova.core.playbook.FixSpec;
import io.renova.core.playbook.Playbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the bundled Java playbook against a small synthetic Java 8 web application. */
class JavaPlaybookTest {

    static PluginRegistry registry;
    static AnalysisResult analysis;

    @BeforeAll
    static void analyze() throws Exception {
        Path fixture = Path.of(JavaPlaybookTest.class.getResource("/fixtures/legacy-webapp").toURI());
        registry = PluginRegistry.load();
        Playbook playbook = registry.defaultPlaybook(fixture);
        analysis = new Analyzer(registry).analyze(fixture, playbook);
    }

    @Test
    void modelsTheMavenModule() {
        assertThat(analysis.project().modules()).singleElement().satisfies(m -> {
            assertThat(m.name()).isEqualTo("acme-shop");
            assertThat(m.fact("javaVersion")).isEqualTo("8");
            assertThat(m.fact("servletSpec")).isEqualTo("3.1");
            assertThat(m.fact("buildVariants")).isEqualTo(List.of("pom.jboss.xml"));
        });
        assertThat(analysis.project().facts().get("containers")).isEqualTo(List.of("jboss", "servlet"));
    }

    @Test
    void findsEveryExpectedRule() {
        Map<String, Long> byRule = analysis.findings().stream()
                .collect(Collectors.groupingBy(Finding::ruleId, Collectors.counting()));
        assertThat(byRule).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("java-level", 1L),
                Map.entry("javax-ee-dependencies", 2L),
                Map.entry("web-xml-schema", 1L),
                Map.entry("maven-variant-poms", 1L),
                Map.entry("javax-ee-imports", 2L),
                Map.entry("javax-in-jsp", 1L),
                Map.entry("spring-framework-6", 2L),
                Map.entry("spring-assert-without-message", 1L),
                Map.entry("handler-interceptor-adapter", 1L),
                Map.entry("sun-misc-base64", 1L),
                Map.entry("cglib", 2L),
                Map.entry("spring-mvc-url-matching", 2L),
                Map.entry("sitemesh2-tomcat10", 2L),
                Map.entry("jboss-deployment-structure", 1L)));
        assertThat(analysis.warnings()).isEmpty();
    }

    @Test
    void doesNotMistakeJsr305OrJdkPackagesForJavaEe() {
        assertThat(analysis.findings())
                .filteredOn(f -> f.ruleId().equals("javax-ee-imports"))
                .extracting(Finding::evidence)
                .containsExactly("javax.annotation.Resource", "javax.servlet.http.HttpServletRequest");
    }

    @Test
    void plansBuildChangesBeforeBehaviourChanges() {
        MigrationPlan plan = new Planner().plan(analysis);
        List<Category> order = plan.steps().stream().map(s -> s.rule().category()).toList();
        assertThat(order).isSortedAccordingTo(Category.BY_EXECUTION_ORDER);
        assertThat(plan.steps().getFirst().rule().id()).isEqualTo("java-level");
        assertThat(plan.occurrencesByStrategy()).containsKeys(FixSpec.RECIPE, FixSpec.REPLACE, FixSpec.AI, FixSpec.MANUAL);
        assertThat(plan.automationRate()).isBetween(0.0, 1.0);
    }

    @Test
    void everyBundledRuleHasADetector() {
        for (Playbook playbook : registry.playbooks()) {
            playbook.rules().forEach(r -> assertThat(registry.detectorFactory(r.detectorType()))
                    .as("detector for rule %s", r.id()).isPresent());
        }
    }
}
