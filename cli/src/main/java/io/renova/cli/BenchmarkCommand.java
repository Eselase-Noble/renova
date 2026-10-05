package io.renova.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.renova.core.ai.AiSettings;
import io.renova.core.engine.MigrationOptions;
import io.renova.core.engine.MigrationOutcome;
import io.renova.core.engine.PluginRegistry;
import io.renova.core.engine.StageResult;
import io.renova.core.playbook.Playbook;
import io.renova.core.rag.RagSettings;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Command(name = "benchmark",
        description = "Migrate a suite of legacy apps with several configurations and score the results "
                + "(build, the suite's checks, repair rounds, AI requests and tokens).")
final class BenchmarkCommand implements Callable<Integer> {

    @Option(names = "--suite", defaultValue = "benchmark/suite.yaml", paramLabel = "FILE",
            description = "Suite file (default: ${DEFAULT-VALUE}).")
    Path suiteFile;

    @Option(names = "--apps", paramLabel = "DIR", description = "Directory holding the apps. Default: the suite's appsDir.")
    Path appsDir;

    @Option(names = "--out", required = true, paramLabel = "DIR", description = "Empty or new directory for the results.")
    Path out;

    @Option(names = "--configs", split = ",", paramLabel = "ID", description = "Only these configurations.")
    List<String> onlyConfigs = new ArrayList<>();

    @Option(names = "--only", split = ",", paramLabel = "APP", description = "Only these apps.")
    List<String> onlyApps = new ArrayList<>();

    @Option(names = "--repeat", defaultValue = "1", description = "Runs per app and configuration (AI results vary).")
    int repeat;

    @Option(names = "--max-ai-iterations", defaultValue = "3", description = "Build-repair rounds (default: ${DEFAULT-VALUE}).")
    int maxAiIterations;

    @Option(names = "--maven-settings", paramLabel = "FILE", description = "Maven settings.xml (e.g. for a private Nexus).")
    Path mavenSettings;

    @Option(names = "--offline", description = "Run Maven offline.")
    boolean offline;

    @Mixin
    AiOptions ai;

    @Override
    public Integer call() throws Exception {
        BenchmarkSuite suite = BenchmarkSuite.load(suiteFile);
        Path apps = appsDir != null ? appsDir
                : suiteFile.toAbsolutePath().getParent().resolve(suite.appsDir() == null ? "." : suite.appsDir()).normalize();
        List<BenchmarkSuite.App> selectedApps = suite.apps().stream()
                .filter(a -> onlyApps.isEmpty() || onlyApps.contains(a.id())).toList();
        List<BenchmarkSuite.Configuration> configs = suite.configurations().stream()
                .filter(c -> onlyConfigs.isEmpty() || onlyConfigs.contains(c.id())).toList();
        if (selectedApps.isEmpty() || configs.isEmpty()) {
            System.err.println("Nothing to run: no app or configuration matches the filters.");
            return 2;
        }
        if (Files.exists(out) && (!Files.isDirectory(out) || hasEntries(out))) {
            System.err.println("Output directory must be new or empty: " + out);
            return 2;
        }

        PluginRegistry registry = PluginRegistry.load();
        AiConfiguration aiConfig = AiConfiguration.load(registry, ai);
        long paidRuns = configs.stream().filter(BenchmarkSuite.Configuration::ai).count() * selectedApps.size() * repeat;
        if (paidRuns > 0) {
            if (aiConfig.aiSettings().equals(AiSettings.NONE)) {
                System.err.println("Configurations " + configs.stream().filter(BenchmarkSuite.Configuration::ai)
                        .map(BenchmarkSuite.Configuration::id).toList() + " need an AI provider (--ai, or renova config).");
                return 2;
            }
            System.err.println("AI: " + aiConfig.describe() + " — " + paidRuns + " migration(s) will use it");
        }

        List<BenchmarkResult> results = new ArrayList<>();
        for (int rep = 1; rep <= repeat; rep++) {
            for (BenchmarkSuite.Configuration config : configs) {
                for (BenchmarkSuite.App app : selectedApps) {
                    String label = config.id() + "/" + app.id() + (repeat > 1 ? "#" + rep : "");
                    Path workspace = out.resolve(config.id()).resolve(repeat > 1 ? app.id() + "-" + rep : app.id());
                    System.err.println("» " + label);
                    BenchmarkResult result = runOne(registry, aiConfig, apps.resolve(app.path()), app, config, rep, workspace, label);
                    results.add(result);
                    System.err.println("  " + label + ": " + oneLine(result));
                }
            }
        }

        Files.createDirectories(out);
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(out.resolve("results.json").toFile(), Map.of("suite", suite.id() == null ? "" : suite.id(),
                        "results", results));
        String scoreboard = scoreboard(suite, results);
        Files.writeString(out.resolve("results.md"), scoreboard);
        System.out.println(scoreboard);
        System.err.println("Results: " + out.resolve("results.md"));
        return results.stream().anyMatch(r -> r.build().equals("ERROR")) ? 1 : 0;
    }

    private BenchmarkResult runOne(PluginRegistry registry, AiConfiguration aiConfig, Path root, BenchmarkSuite.App app,
                                   BenchmarkSuite.Configuration config, int rep, Path workspace, String label) {
        long start = System.nanoTime();
        try {
            Map<String, String> tools = new LinkedHashMap<>();
            if (mavenSettings != null) {
                tools.put("maven.settings", mavenSettings.toString());
            }
            if (offline) {
                tools.put("maven.offline", "true");
            }
            if (config.skipTests()) {
                tools.put("verify.skipTests", "true");
            }
            RagSettings rag = config.rag() ? new RagSettings(true, aiConfig.ragSettings(true).budget()) : RagSettings.OFF;
            MigrationOptions options = new MigrationOptions(workspace, config.ai() ? aiConfig.aiSettings() : AiSettings.NONE,
                    maxAiIterations, true, tools, config.skip(), rag);
            Playbook playbook = app.playbook() == null ? registry.defaultPlaybook(root) : registry.playbook(app.playbook());
            MigrationRun run = MigrationRun.execute(registry, root, playbook, options,
                    msg -> System.err.println("    [" + label + "] " + msg), (a, p) -> { });
            MigrationOutcome outcome = run.outcome();

            List<String> failed = new ArrayList<>();
            for (BenchmarkSuite.Check check : app.checks()) {
                if (!passes(outcome.workspace(), check)) {
                    failed.add(check.describe());
                }
            }
            int rejected = (int) outcome.stages().stream().flatMap(s -> s.details().stream())
                    .filter(l -> l.contains("rejected edit to")).count();
            String build = outcome.verification() == null ? "NOT_VERIFIED" : outcome.verification().success() ? "PASSES" : "FAILS";
            return new BenchmarkResult(app.id(), config.id(), rep, build,
                    outcome.verification() == null ? 0 : outcome.verification().errors().size(),
                    app.checks().size() - failed.size(), app.checks().size(), failed, outcome.repairRounds(), outcome.aiUsage(),
                    rejected, outcome.manualSteps().size(), run.plan().automationRate(), seconds(start),
                    outcome.workspace().toString(), stageFailures(outcome));
        } catch (Exception e) {
            return new BenchmarkResult(app.id(), config.id(), rep, "ERROR", 0, 0, app.checks().size(), List.of(), 0,
                    io.renova.core.ai.AiUsage.NONE, 0, 0, 0, seconds(start), workspace.toString(),
                    e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    static boolean passes(Path workspace, BenchmarkSuite.Check check) throws IOException {
        List<String> contents = new ArrayList<>();
        for (Path file : matching(workspace, check.file())) {
            contents.add(Files.readString(file, StandardCharsets.ISO_8859_1));
        }
        if (check.absent() != null) {
            return contents.stream().noneMatch(c -> c.contains(check.absent()));
        }
        if (check.contains() != null) {
            return contents.stream().anyMatch(c -> c.contains(check.contains()));
        }
        Pattern pattern = Pattern.compile(check.matches());
        return contents.stream().anyMatch(c -> pattern.matcher(c).find());
    }

    private static List<Path> matching(Path workspace, String file) throws IOException {
        if (!file.contains("*") && !file.contains("?") && !file.contains("{")) {
            Path path = workspace.resolve(file);
            return Files.isRegularFile(path) ? List.of(path) : List.of();
        }
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + file);
        try (Stream<Path> files = Files.walk(workspace)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> {
                        Path rel = workspace.relativize(p);
                        return !rel.startsWith(".git") && !rel.startsWith(".renova") && !rel.toString().contains("/target/")
                                && !rel.startsWith("target") && matcher.matches(rel);
                    })
                    .toList();
        }
    }

    /** Stages that failed outright, such as a recipe run that could not start. */
    private static String stageFailures(MigrationOutcome outcome) {
        List<String> failed = outcome.stages().stream().filter(s -> s.status() == StageResult.Status.FAILED)
                .map(s -> s.stage() + ": " + s.summary()).toList();
        return failed.isEmpty() ? null : String.join("; ", failed);
    }

    private static String oneLine(BenchmarkResult r) {
        if (r.build().equals("ERROR")) {
            return "ERROR " + r.error();
        }
        return "build " + r.build() + ", checks " + r.checksPassed() + "/" + r.checksTotal() + ", repair rounds "
                + r.repairRounds() + ", AI requests " + r.ai().requests() + ", tokens " + r.ai().inputTokens() + "/"
                + r.ai().outputTokens() + String.format(Locale.ROOT, ", %.0fs", r.seconds());
    }

    static String scoreboard(BenchmarkSuite suite, List<BenchmarkResult> results) {
        StringBuilder md = new StringBuilder("# Renova benchmark" + (suite.id() == null ? "" : ": " + suite.id()) + "\n\n");
        md.append("## By configuration\n\n");
        md.append("| Configuration | Runs passed | Builds pass | Checks passed | Repair rounds | AI requests | Input / output tokens | Time |\n");
        md.append("|---|---|---|---|---|---|---|---|\n");
        Map<String, List<BenchmarkResult>> byConfig = new LinkedHashMap<>();
        results.forEach(r -> byConfig.computeIfAbsent(r.configuration(), k -> new ArrayList<>()).add(r));
        byConfig.forEach((config, rows) -> md.append(String.format(Locale.ROOT, "| %s | %d/%d | %d/%d | %d/%d | %d | %d | %d / %d | %.0fs |%n",
                config,
                rows.stream().filter(BenchmarkResult::passed).count(), rows.size(),
                rows.stream().filter(r -> r.build().equals("PASSES")).count(), rows.size(),
                rows.stream().mapToInt(BenchmarkResult::checksPassed).sum(), rows.stream().mapToInt(BenchmarkResult::checksTotal).sum(),
                rows.stream().mapToInt(BenchmarkResult::repairRounds).sum(),
                rows.stream().mapToInt(r -> r.ai().requests()).sum(),
                rows.stream().mapToLong(r -> r.ai().inputTokens()).sum(), rows.stream().mapToLong(r -> r.ai().outputTokens()).sum(),
                rows.stream().mapToDouble(BenchmarkResult::seconds).sum())));

        md.append("\n## By run\n\n");
        md.append("| App | Configuration | Build | Checks | Repair rounds | AI requests (changed / unchanged / declined) | Tokens in / out | Notes / reference files | Manual steps | Time |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (BenchmarkResult r : results) {
            md.append(String.format(Locale.ROOT, "| %s | %s | %s | %d/%d | %d | %d (%d / %d / %d) | %d / %d | %d / %d | %d | %.0fs |%n",
                    r.app() + (r.repetition() > 1 ? " #" + r.repetition() : ""), r.configuration(),
                    r.build().equals("FAILS") ? "FAILS (" + r.buildErrors() + ")" : r.build(),
                    r.checksPassed(), r.checksTotal(), r.repairRounds(),
                    r.ai().requests(), r.ai().changed(), r.ai().unchanged(), r.ai().declined(),
                    r.ai().inputTokens(), r.ai().outputTokens(), r.ai().knowledgeNotes(), r.ai().referenceFiles(),
                    r.manualSteps(), r.seconds()));
        }
        List<BenchmarkResult> problems = results.stream()
                .filter(r -> !r.failedChecks().isEmpty() || r.error() != null || r.rejectedEdits() > 0).toList();
        if (!problems.isEmpty()) {
            md.append("\n## Problems\n\n");
            for (BenchmarkResult r : problems) {
                String run = r.configuration() + "/" + r.app() + (r.repetition() > 1 ? " #" + r.repetition() : "");
                r.failedChecks().forEach(c -> md.append("- ").append(run).append(": check failed: ").append(c).append('\n'));
                if (r.rejectedEdits() > 0) {
                    md.append("- ").append(run).append(": ").append(r.rejectedEdits()).append(" AI edit(s) rejected\n");
                }
                if (r.error() != null) {
                    md.append("- ").append(run).append(": ").append(r.error()).append('\n');
                }
            }
        }
        return md.toString();
    }

    private static double seconds(long start) {
        return (System.nanoTime() - start) / 1e9;
    }

    private static boolean hasEntries(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findAny().isPresent();
        }
    }
}
