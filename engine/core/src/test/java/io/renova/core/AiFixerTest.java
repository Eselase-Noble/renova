package io.renova.core;

import io.renova.core.ai.AiFixer;
import io.renova.core.ai.AiProvider;
import io.renova.core.ai.AiSettings;
import io.renova.core.ai.FixRequest;
import io.renova.core.ai.Proposal;
import io.renova.core.ai.RequestFile;
import io.renova.core.engine.BuildError;
import io.renova.core.engine.MigrationContext;
import io.renova.core.engine.MigrationOptions;
import io.renova.core.engine.VerifyResult;
import io.renova.core.model.Module;
import io.renova.core.model.ProjectModel;
import io.renova.core.playbook.Playbook;
import io.renova.core.spi.EcosystemPlugin;
import io.renova.core.spi.RelatedFile;
import io.renova.core.spi.Verifier;
import io.renova.core.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** The repair loop with a scripted model and build, so multi-file behaviour is tested without an API. */
class AiFixerTest {

    /** Sources under src/ belong to build.txt (editable); variant.txt references build.txt read-only. */
    static class ToyPlugin implements EcosystemPlugin {
        public String id() { return "toy"; }
        public String displayName() { return "Toy"; }
        public boolean supports(Path root) { return true; }
        public ProjectModel model(Path root) {
            return new ProjectModel(root, "toy", List.of(new Module("toy", ".", "build.txt", Map.of())), Map.of());
        }
        @Override
        public List<RelatedFile> relatedFiles(ProjectModel model, String file) {
            if (file.startsWith("src/")) {
                return List.of(new RelatedFile("build.txt", true, "build file of module toy"));
            }
            if (file.equals("variant.txt")) {
                return List.of(new RelatedFile("build.txt", false, "main build file"));
            }
            return List.of();
        }
    }

    /** Records requests and answers each with the scripted edits. */
    static final class ScriptedAi implements AiProvider {
        final List<FixRequest> requests = new ArrayList<>();
        final Function<FixRequest, Map<String, String>> script;
        ScriptedAi(Function<FixRequest, Map<String, String>> script) { this.script = script; }
        public String name() { return "scripted"; }
        public Proposal propose(FixRequest request) {
            requests.add(request);
            return Proposal.changed(script.apply(request), "scripted", 100, 10);
        }
    }

    private MigrationContext context(Path tmp, AiProvider ai) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("project/src"));
        Files.writeString(project.resolve("A.java"), "uses lib\n");
        Files.writeString(project.resolve("B.java"), "uses lib\n");
        Files.writeString(project.getParent().resolve("build.txt"), "deps:\n");
        Files.writeString(project.getParent().resolve("variant.txt"), "variant deps:\n");
        Files.writeString(project.getParent().resolve("secret.txt"), "do not touch\n");
        Workspace ws = Workspace.create(project.getParent(), tmp.resolve("out"));
        ToyPlugin plugin = new ToyPlugin();
        Playbook playbook = new Playbook("p", "Toy upgrade", null, "toy", "1", null, null, null);
        return new MigrationContext(ws, plugin.model(ws.root()), playbook,
                new MigrationOptions(tmp.resolve("out"), AiSettings.NONE, 3, true, Map.of(), List.of()), ai, plugin);
    }

    @Test
    void fixesErrorsInSeveralSourceFilesWithOneBuildFileEdit(@TempDir Path tmp) throws Exception {
        ScriptedAi ai = new ScriptedAi(request -> {
            Map<String, String> edits = new LinkedHashMap<>();
            edits.put("build.txt", "deps:\n  lib\n");
            edits.put("secret.txt", "overwritten\n");          // not offered: must be rejected
            edits.put("../escape.txt", "outside workspace\n");  // traversal: must be rejected
            return edits;
        });
        MigrationContext ctx = context(tmp, ai);
        Path root = ctx.workspace().root();
        Verifier build = c -> Files.readString(root.resolve("build.txt")).contains("lib")
                ? new VerifyResult(true, List.of(), "")
                : new VerifyResult(false, List.of(), "");
        VerifyResult failing = new VerifyResult(false, List.of(
                new BuildError("src/A.java", 1, "package lib does not exist"),
                new BuildError("src/B.java", 1, "package lib does not exist")), "");

        List<String> log = new ArrayList<>();
        VerifyResult result = new AiFixer().repair(ctx, build, failing, 3, log);

        assertThat(result.success()).isTrue();
        assertThat(ai.requests).singleElement().satisfies(r -> {
            assertThat(r.files()).extracting(RequestFile::path, RequestFile::role).containsExactly(
                    org.assertj.core.groups.Tuple.tuple("src/A.java", RequestFile.Role.TARGET),
                    org.assertj.core.groups.Tuple.tuple("src/B.java", RequestFile.Role.TARGET),
                    org.assertj.core.groups.Tuple.tuple("build.txt", RequestFile.Role.RELATED));
            assertThat(r.errors()).hasSize(2);
        });
        assertThat(Files.readString(root.resolve("build.txt"))).contains("lib");
        assertThat(Files.readString(root.resolve("secret.txt"))).isEqualTo("do not touch\n");
        assertThat(tmp.resolve("escape.txt")).doesNotExist();
        Path audit = root.resolve(".renova/ai/001.md");
        assertThat(audit).exists();
        assertThat(Files.readString(audit)).contains("# AI exchange 1", "`src/A.java` (target)",
                "`build.txt` (related: build file of module toy)", "package lib does not exist");
        assertThat(log).anyMatch(l -> l.contains("rejected edit to secret.txt"))
                .anyMatch(l -> l.contains("rejected edit to ../escape.txt"))
                .anyMatch(l -> l.contains("round 1: edited build.txt; build passes"));
    }

    @Test
    void referenceFilesAreShownButNeverWritten(@TempDir Path tmp) throws Exception {
        ScriptedAi ai = new ScriptedAi(request -> Map.of(
                "variant.txt", "variant deps:\n  lib\n",
                "build.txt", "changed by mistake\n"));
        MigrationContext ctx = context(tmp, ai);
        Path root = ctx.workspace().root();
        VerifyResult failing = new VerifyResult(false, List.of(new BuildError("variant.txt", 0, "out of date")), "");
        Verifier build = c -> new VerifyResult(true, List.of(), "");

        new AiFixer().repair(ctx, build, failing, 1, new ArrayList<>());

        assertThat(ai.requests.getFirst().files()).extracting(RequestFile::path, RequestFile::role).containsExactly(
                org.assertj.core.groups.Tuple.tuple("variant.txt", RequestFile.Role.TARGET),
                org.assertj.core.groups.Tuple.tuple("build.txt", RequestFile.Role.REFERENCE));
        assertThat(Files.readString(root.resolve("variant.txt"))).contains("lib");
        assertThat(Files.readString(root.resolve("build.txt"))).isEqualTo("deps:\n");
    }

    @Test
    void keepsRepairingWhenTheSameBuildFileNeedsAnotherEditNextRound(@TempDir Path tmp) throws Exception {
        // Round 1 fixes the build settings, round 2 adds the dependency: both edit build.txt.
        ScriptedAi ai = new ScriptedAi(request -> {
            String build = request.files().stream().filter(f -> f.path().equals("build.txt"))
                    .findFirst().orElseThrow().content();
            return Map.of("build.txt", build.contains("target") ? build + "  lib\n" : build + "  target\n");
        });
        MigrationContext ctx = context(tmp, ai);
        Path root = ctx.workspace().root();
        Verifier build = c -> {
            String b = Files.readString(root.resolve("build.txt"));
            if (!b.contains("target")) {
                return new VerifyResult(false, List.of(new BuildError("build.txt", 0, "target missing")), "");
            }
            return b.contains("lib") ? new VerifyResult(true, List.of(), "")
                    : new VerifyResult(false, List.of(new BuildError("src/A.java", 1, "package lib does not exist")), "");
        };
        List<String> log = new ArrayList<>();
        VerifyResult result = new AiFixer().repair(ctx, build, build.verify(ctx), 3, log);

        assertThat(result.success()).isTrue();
        assertThat(ai.requests).hasSize(2);
        assertThat(log).contains("round 1: edited build.txt; build still fails", "round 2: edited build.txt; build passes");
    }

    @Test
    void failingTestsAreShownButNeverEdited(@TempDir Path tmp) throws Exception {
        ScriptedAi ai = new ScriptedAi(request -> Map.of("src/ATest.java", "assertTrue(true)\n", "build.txt", "deps:\n  lib\n"));
        MigrationContext base = context(tmp, ai);
        Files.writeString(base.workspace().root().resolve("src/ATest.java"), "assertEquals(1, a())\n");
        ToyPlugin plugin = new ToyPlugin() {
            @Override
            public boolean isTestFile(String file) {
                return file.endsWith("Test.java");
            }
        };
        MigrationContext ctx = new MigrationContext(base.workspace(), base.project(), base.playbook(), base.options(), ai, plugin);
        VerifyResult failing = new VerifyResult(false, List.of(new BuildError("src/ATest.java", 1, "expected 1 but was 2")), "");
        List<String> log = new ArrayList<>();

        new AiFixer().repair(ctx, c -> new VerifyResult(true, List.of(), ""), failing, 1, log);

        assertThat(ai.requests.getFirst().files()).extracting(RequestFile::path, RequestFile::role).containsExactly(
                org.assertj.core.groups.Tuple.tuple("src/ATest.java", RequestFile.Role.REFERENCE),
                org.assertj.core.groups.Tuple.tuple("build.txt", RequestFile.Role.RELATED));
        assertThat(Files.readString(base.workspace().root().resolve("src/ATest.java"))).isEqualTo("assertEquals(1, a())\n");
        assertThat(log).anyMatch(l -> l.contains("rejected edit to src/ATest.java"));
    }

    @Test
    void aTestTheMigrationLeftUncompilableMayBeRepaired(@TempDir Path tmp) throws Exception {
        ScriptedAi ai = new ScriptedAi(request -> Map.of("src/A.java", "accept(builder)\n"));
        MigrationContext base = context(tmp, ai);
        Path rewritten = base.workspace().root().resolve("src/A.java");
        Files.writeString(rewritten, "accept(builder.build())\n");
        base.workspace().commitAll("recipe stage");
        // Every source file of this project is a test; no build file to group them under.
        ToyPlugin plugin = new ToyPlugin() {
            @Override
            public boolean isTestFile(String file) {
                return file.startsWith("src/");
            }
            @Override
            public List<RelatedFile> relatedFiles(ProjectModel model, String file) {
                return List.of();
            }
        };
        MigrationContext ctx = new MigrationContext(base.workspace(), base.project(), base.playbook(), base.options(), ai, plugin);
        VerifyResult failing = new VerifyResult(false, List.of(
                new BuildError("src/A.java", 1, "incompatible types: Response cannot be converted to Builder"),
                new BuildError("src/B.java", 1, "cannot find symbol")), "");

        new AiFixer().repair(ctx, c -> new VerifyResult(true, List.of(), ""), failing, 1, new ArrayList<>());

        // Rewritten by a stage and rejected by the compiler: editable. Never touched by the migration: not.
        assertThat(ai.requests.getFirst().files()).extracting(RequestFile::path, RequestFile::role).containsExactly(
                org.assertj.core.groups.Tuple.tuple("src/A.java", RequestFile.Role.TARGET),
                org.assertj.core.groups.Tuple.tuple("src/B.java", RequestFile.Role.REFERENCE));
        assertThat(Files.readString(rewritten)).isEqualTo("accept(builder)\n");
    }

    @Test
    void aTestThatRunsAndFailsIsNeverEditedEvenIfTheMigrationChangedIt(@TempDir Path tmp) throws Exception {
        ScriptedAi ai = new ScriptedAi(request -> Map.of("src/A.java", "assertTrue(true)\n"));
        MigrationContext base = context(tmp, ai);
        Path test = base.workspace().root().resolve("src/A.java");
        Files.writeString(test, "assertEquals(1, a())\n");
        base.workspace().commitAll("recipe stage");
        ToyPlugin plugin = new ToyPlugin() {
            @Override
            public boolean isTestFile(String file) {
                return file.startsWith("src/");
            }
        };
        MigrationContext ctx = new MigrationContext(base.workspace(), base.project(), base.playbook(), base.options(), ai, plugin);
        VerifyResult failing = new VerifyResult(false,
                List.of(new BuildError("src/A.java", 1, "test A.adds failed: expected 1 but was 2")), "");

        new AiFixer().repair(ctx, c -> new VerifyResult(true, List.of(), ""), failing, 1, new ArrayList<>());

        assertThat(ai.requests.getFirst().files().getFirst().role()).isEqualTo(RequestFile.Role.REFERENCE);
        assertThat(Files.readString(test)).isEqualTo("assertEquals(1, a())\n");
    }

    @Test
    void aRuleThatReplacesAWholeLayerSendsItsFilesTogetherAndMayAddNewOnes(@TempDir Path tmp) throws Exception {
        ScriptedAi ai = new ScriptedAi(request -> Map.of(
                "src/A.java", "controller A\n", "pages/a.page", "new tags\n",
                "src/Config.java", "wiring\n",          // allowed: matches params.create
                "notes/README.txt", "not asked for\n")); // not offered and not creatable
        MigrationContext ctx = context(tmp, ai);
        Path root = ctx.workspace().root();
        Files.createDirectories(root.resolve("pages"));
        Files.writeString(root.resolve("pages/a.page"), "old tags\n");
        io.renova.core.playbook.FixSpec fix = new io.renova.core.playbook.FixSpec("ai", null, "replace the framework", null, null, null,
                false, Map.of("together", true, "with", List.of("pages/*.page"), "create", List.of("src/*.java")));
        io.renova.core.playbook.Rule rule = new io.renova.core.playbook.Rule("old-framework", "Old framework",
                io.renova.core.model.Category.API, io.renova.core.model.Severity.BLOCKER, Map.of("type", "fileExists"), fix, null, null);
        io.renova.core.engine.PlanStep step = new io.renova.core.engine.PlanStep(1, rule, 2, List.of("src/A.java", "src/B.java"), List.of());

        new AiFixer().apply(ctx, List.of(step));

        assertThat(ai.requests).hasSize(1);
        assertThat(ai.requests.getFirst().files()).extracting(RequestFile::path, RequestFile::role).containsExactly(
                org.assertj.core.groups.Tuple.tuple("src/A.java", RequestFile.Role.TARGET),
                org.assertj.core.groups.Tuple.tuple("src/B.java", RequestFile.Role.TARGET),
                org.assertj.core.groups.Tuple.tuple("build.txt", RequestFile.Role.RELATED),
                org.assertj.core.groups.Tuple.tuple("pages/a.page", RequestFile.Role.RELATED));
        assertThat(ai.requests.getFirst().creatable()).containsExactly("src/*.java");
        assertThat(Files.readString(root.resolve("src/A.java"))).isEqualTo("controller A\n");
        assertThat(Files.readString(root.resolve("pages/a.page"))).isEqualTo("new tags\n");
        assertThat(Files.readString(root.resolve("src/Config.java"))).isEqualTo("wiring\n");
        assertThat(root.resolve("notes/README.txt")).doesNotExist();
    }
}
