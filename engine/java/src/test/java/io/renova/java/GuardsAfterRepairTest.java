package io.renova.java;

import io.renova.core.ai.AiProvider;
import io.renova.core.ai.AiProviderFactory;
import io.renova.core.ai.AiSettings;
import io.renova.core.ai.FixRequest;
import io.renova.core.ai.Proposal;
import io.renova.core.engine.AnalysisResult;
import io.renova.core.engine.Analyzer;
import io.renova.core.engine.BuildError;
import io.renova.core.engine.MigrationOptions;
import io.renova.core.engine.MigrationOutcome;
import io.renova.core.engine.Migrator;
import io.renova.core.engine.Planner;
import io.renova.core.engine.PluginRegistry;
import io.renova.core.engine.StageResult;
import io.renova.core.engine.VerifyResult;
import io.renova.core.model.ProjectModel;
import io.renova.core.playbook.Playbook;
import io.renova.core.spi.DetectorFactory;
import io.renova.core.spi.EcosystemPlugin;
import io.renova.core.spi.Fixer;
import io.renova.core.spi.RelatedFile;
import io.renova.core.spi.Verifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards check the AI repair loop's edits before each rebuild. No Maven or network: the build is scripted. */
class GuardsAfterRepairTest {

    private static final String POM = """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>g</groupId>
                <artifactId>web</artifactId>
                <version>1</version>
                <packaging>war</packaging>
                <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                </properties>
                <dependencies>
                </dependencies>
                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.apache.maven.plugins</groupId>
                            <artifactId>maven-war-plugin</artifactId>
                            <version>3.4.0</version>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """;

    /** The real Java plugin, with a build that fails until the servlet API is declared with provided scope. */
    static final class ScriptedBuildPlugin implements EcosystemPlugin {
        final JavaPlugin java = new JavaPlugin();
        int builds;

        public String id() { return java.id(); }
        public String displayName() { return java.displayName(); }
        public boolean supports(Path root) { return java.supports(root); }
        public ProjectModel model(Path root) throws IOException { return java.model(root); }
        public List<DetectorFactory> detectors() { return java.detectors(); }
        public List<Fixer> fixers() { return java.fixers(); }
        public List<RelatedFile> relatedFiles(ProjectModel model, String file) { return java.relatedFiles(model, file); }
        public List<String> bundledPlaybooks() { return java.bundledPlaybooks(); }

        @Override
        public Optional<Verifier> verifier() {
            return Optional.of(context -> {
                builds++;
                String pom = Files.readString(context.workspace().root().resolve("pom.xml"));
                return pom.contains("<scope>provided</scope>") ? new VerifyResult(true, List.of(), "")
                        : new VerifyResult(false, List.of(new BuildError("src/main/java/web/Hello.java", 3,
                                "package jakarta.servlet does not exist")), "");
            });
        }
    }

    /** Answers like a model that declares the missing API but forgets the scope. */
    static final class CompileScopeAi implements AiProviderFactory {
        public String name() { return "scripted"; }
        public String displayName() { return "Scripted"; }
        public String defaultModel() { return "scripted"; }
        public String apiKeyEnvironmentVariable() { return "SCRIPTED_KEY"; }

        public AiProvider create(AiSettings settings) {
            return new AiProvider() {
                public String name() { return "scripted"; }
                public Proposal propose(FixRequest request) {
                    return Proposal.changed(Map.of("pom.xml", POM.replace("<dependencies>\n", """
                            <dependencies>
                                    <dependency>
                                        <groupId>jakarta.servlet</groupId>
                                        <artifactId>jakarta.servlet-api</artifactId>
                                        <version>6.0.0</version>
                                    </dependency>
                            """)), "declared the servlet API", 100, 50);
                }
            };
        }
    }

    @Test
    void guardsFixWhatTheRepairLoopIntroducesBeforeTheRebuild(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), POM);
        Files.createDirectories(project.resolve("src/main/java/web"));
        Files.writeString(project.resolve("src/main/java/web/Hello.java"), "package web;\n\nclass Hello {}\n");

        ScriptedBuildPlugin plugin = new ScriptedBuildPlugin();
        PluginRegistry registry = new PluginRegistry(List.of(plugin), List.of(new CompileScopeAi()));
        Playbook playbook = registry.defaultPlaybook(project);
        AnalysisResult analysis = new Analyzer(registry).analyze(project, playbook);
        MigrationOutcome outcome = new Migrator(registry, m -> { }).migrate(analysis, new Planner().plan(analysis),
                new MigrationOptions(tmp.resolve("out"), new AiSettings("scripted", null, null, null, Map.of()), 2, true,
                        Map.of(), List.of("recipe")));

        // Round 1 added the API with compile scope; the guard set it to provided before the rebuild.
        assertThat(outcome.verification().success()).isTrue();
        assertThat(outcome.repairRounds()).isEqualTo(1);
        assertThat(plugin.builds).isEqualTo(2);
        assertThat(outcome.stages()).extracting(StageResult::stage)
                .contains("guard after repair round 1", "guard after repair round 1 maven");
        assertThat(Files.readString(tmp.resolve("out/pom.xml")))
                .contains("<artifactId>jakarta.servlet-api</artifactId>\n            <version>6.0.0</version>\n            <scope>provided</scope>");
    }
}
