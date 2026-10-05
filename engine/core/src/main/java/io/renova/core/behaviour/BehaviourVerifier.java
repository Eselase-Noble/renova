package io.renova.core.behaviour;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.renova.core.engine.MigrationContext;
import io.renova.core.model.ProjectModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Behavioural verification: runs the original and the migrated application side by side, sends both
 * the same requests and reports every answer that differs. Writes {@code .renova/behaviour.md} and
 * {@code .renova/behaviour.json} in the workspace.
 */
public final class BehaviourVerifier {

    /** Enough to cover an application's entry points without making verification slow. */
    public static final int MAX_SCENARIOS = 60;
    /** Tool option naming a scenario file other than renova-scenarios.yaml in the project. */
    public static final String SCENARIOS_OPTION = "behaviour.scenarios";
    private static final int MAX_BODY_IN_REPORT = 2000;

    private BehaviourVerifier() {
    }

    public static BehaviourReport verify(MigrationContext context, Consumer<String> progress) {
        BehaviourReport report;
        try {
            report = run(context, progress);
        } catch (Exception e) {
            report = BehaviourReport.failed(e.getMessage() == null ? e.toString() : e.getMessage(), null, null);
        }
        write(context.workspace().lcDir(), report);
        return report;
    }

    private static BehaviourReport run(MigrationContext context, Consumer<String> progress) throws Exception {
        Optional<BehaviourRunner> runner = context.plugin().behaviourRunner();
        if (runner.isEmpty()) {
            return BehaviourReport.skipped("the " + context.plugin().displayName() + " plugin cannot run applications");
        }
        // Routes, scenarios and the original build all come from the workspace's baseline commit: exactly
        // what was migrated, even if the project has changed since.
        Path workDir = context.workspace().lcDir().resolve("behaviour");
        Path original = originalSource(context, workDir);
        ProjectModel originalModel = context.plugin().model(original);
        Optional<String> unsupported = runner.get().unsupported(originalModel);
        if (unsupported.isPresent()) {
            return BehaviourReport.skipped(unsupported.get());
        }
        List<Scenario> scenarios = new ArrayList<>(runner.get().discover(originalModel, original));
        if (scenarios.size() > MAX_SCENARIOS) {
            scenarios = new ArrayList<>(scenarios.subList(0, MAX_SCENARIOS));
        }
        Path scenarioPath = scenarioFile(context, original);
        ScenarioFile file = scenarioPath == null ? null : ScenarioFile.load(scenarioPath);
        if (file != null) {
            scenarios.addAll(file.toScenarios(scenarioPath.getParent()));
        }
        if (scenarios.isEmpty()) {
            return BehaviourReport.skipped("no entry points found to send requests to");
        }
        if (!DockerSandbox.available()) {
            return BehaviourReport.skipped("Docker is not available; behavioural verification runs applications in containers");
        }
        BehaviourRunner.Deployments deployments = runner.get().prepare(context, original, workDir, progress);

        DockerSandbox.Database database = null;
        if (file != null && file.database() != null) {
            ScenarioFile.Database db = file.database();
            if (db.init() == null || !Files.isRegularFile(scenarioPath.getParent().resolve(db.init()))) {
                throw new IllegalArgumentException(ScenarioFile.DEFAULT_NAME + ": database.init must name an SQL file next to it");
            }
            database = new DockerSandbox.Database(db.image(), scenarioPath.getParent().resolve(db.init()), db.ignoreColumns());
            deployments = new BehaviourRunner.Deployments(
                    deployments.baseline().withEnvironment(runner.get().environment(withHost(db.app(), DockerSandbox.BASELINE_DB))),
                    deployments.candidate().withEnvironment(runner.get().environment(withHost(db.app(), DockerSandbox.CANDIDATE_DB))));
        }
        DockerSandbox.Run run = DockerSandbox.run(deployments, scenarios, database, workDir, progress);
        String baselinePlatform = deployments.baseline().platform();
        String candidatePlatform = deployments.candidate().platform();
        if (!run.baselineReady()) {
            return new BehaviourReport(BehaviourReport.Status.FAILED, "the original application did not start on "
                    + baselinePlatform, baselinePlatform, candidatePlatform, List.of(), Map.of(), run.baselineLog(), run.candidateLog());
        }
        if (!run.candidateReady()) {
            return new BehaviourReport(BehaviourReport.Status.DIFFERENT, "the migrated application did not start on "
                    + candidatePlatform, baselinePlatform, candidatePlatform, List.of(), Map.of(), run.baselineLog(), run.candidateLog());
        }
        List<ScenarioResult> results = new ArrayList<>();
        for (Scenario scenario : scenarios) {
            for (int i = 0; i < scenario.steps().size(); i++) {
                DockerSandbox.Answers answers = run.answers().get(scenario.id() + ":" + i);
                results.add(answers == null
                        ? new ScenarioResult(scenario, i, Exchange.failed("not sent"), Exchange.failed("not sent"), List.of(),
                                List.of("no answers recorded"))
                        : ResponseComparator.compare(scenario, i, answers.baseline(), answers.baselineAgain(), answers.candidate()));
            }
        }
        long differing = results.stream().filter(r -> !r.same()).count();
        long databases = run.databases().values().stream().filter(d -> !d.isEmpty()).count();
        String summary = (differing == 0 ? "all " + results.size() + " request(s) answered the same"
                : differing + " of " + results.size() + " request(s) answered differently")
                + (run.databases().isEmpty() ? "" : databases == 0 ? "; database changes the same in all "
                        + run.databases().size() + " scenario(s)" : "; " + databases + " of " + run.databases().size()
                        + " scenario(s) changed the database differently");
        return new BehaviourReport(differing + databases == 0 ? BehaviourReport.Status.SAME : BehaviourReport.Status.DIFFERENT,
                summary, baselinePlatform, candidatePlatform, results, run.databases(), run.baselineLog(), run.candidateLog());
    }

    /** The project as it was before migration: the workspace's first commit, exported to {@code workDir/original}. */
    private static Path originalSource(MigrationContext context, Path workDir) throws IOException, InterruptedException {
        Path workspace = context.workspace().root();
        if (!context.workspace().versioned()) {
            return context.project().root();
        }
        Path target = workDir.resolve("original");
        deleteRecursively(target);
        Files.createDirectories(target);
        DockerSandbox.Result first = DockerSandbox.exec(List.of("git", "rev-list", "--max-parents=0", "HEAD"), workspace, 60);
        if (first.exit() != 0) {
            throw new IOException("Could not find the workspace's baseline commit: " + first.output().strip());
        }
        String commit = first.output().strip().lines().reduce((a, b) -> b).orElseThrow();
        Path tar = workDir.resolve("original.tar");
        for (List<String> command : List.of(List.of("git", "archive", "-o", tar.toString(), commit),
                List.of("tar", "-xf", tar.toString(), "-C", target.toString()))) {
            DockerSandbox.Result result = DockerSandbox.exec(command, workspace, 300);
            if (result.exit() != 0) {
                throw new IOException("Could not export the original sources: " + result.output().strip());
            }
        }
        Files.delete(tar);
        return target;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    /** {@code behaviour.scenarios} if set, else renova-scenarios.yaml in the original sources; null if none. */
    private static Path scenarioFile(MigrationContext context, Path original) {
        String configured = context.options().toolOption(SCENARIOS_OPTION);
        if (configured != null) {
            Path path = Path.of(configured);
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException("Scenario file not found: " + path);
            }
            return path;
        }
        Path candidate = original.resolve(ScenarioFile.DEFAULT_NAME);
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    private static Map<String, String> withHost(Map<String, String> settings, String host) {
        Map<String, String> result = new LinkedHashMap<>();
        settings.forEach((k, v) -> result.put(k, v.replace("${host}", host)));
        return result;
    }

    static void write(Path lcDir, BehaviourReport report) {
        try {
            Files.createDirectories(lcDir);
            Files.writeString(lcDir.resolve("behaviour.md"), markdown(report));
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(lcDir.resolve("behaviour.json").toFile(),
                    json(report));
        } catch (IOException e) {
            // The reports are a convenience; the outcome is also in the migration report.
        }
    }

    public static String markdown(BehaviourReport report) {
        StringBuilder md = new StringBuilder("# Behavioural verification\n\n");
        md.append("**Result:** ").append(report.status()).append(": ").append(report.summary()).append("\n\n");
        if (report.baselinePlatform() != null) {
            md.append("- Original: ").append(report.baselinePlatform()).append('\n');
            md.append("- Migrated: ").append(report.candidatePlatform()).append("\n\n");
        }
        List<ScenarioResult> differing = report.results().stream().filter(r -> !r.same()).toList();
        if (!differing.isEmpty()) {
            md.append("## Differences\n\n");
            for (ScenarioResult r : differing) {
                md.append("### `").append(r.label()).append("`\n\n");
                md.append("From ").append(r.scenario().why()).append(".\n\n");
                r.differences().forEach(d -> md.append("- ").append(d).append('\n'));
                md.append('\n');
            }
        }
        if (!report.databases().isEmpty()) {
            md.append("## Database changes\n\n| Scenario | Result |\n|---|---|\n");
            report.databases().forEach((id, diffs) -> md.append("| `").append(id).append("` | ")
                    .append(diffs.isEmpty() ? "same rows added and removed" : "**different**: "
                            + String.join("; ", diffs).replace("|", "\\|")).append(" |\n"));
            md.append('\n');
        }
        if (!report.results().isEmpty()) {
            md.append("## All requests\n\n| Request | Original | Migrated | Result |\n|---|---|---|---|\n");
            for (ScenarioResult r : report.results()) {
                md.append("| `").append(r.label()).append("` | ")
                        .append(status(r.baseline())).append(" | ").append(status(r.candidate())).append(" | ")
                        .append(r.same() ? "same" + (r.notes().isEmpty() ? "" : " (" + String.join("; ", r.notes()) + ")")
                                : "**different**").append(" |\n");
            }
            md.append('\n');
        }
        if (report.status() != BehaviourReport.Status.SAME && report.candidateLog() != null) {
            md.append("## Migrated application log (last lines)\n\n```\n").append(report.candidateLog()).append("\n```\n\n");
        }
        if (report.status() == BehaviourReport.Status.FAILED && report.baselineLog() != null) {
            md.append("## Original application log (last lines)\n\n```\n").append(report.baselineLog()).append("\n```\n");
        }
        return md.toString();
    }

    private static String status(Exchange e) {
        return e.responded() ? String.valueOf(e.status()) : "no answer";
    }

    private static Map<String, Object> json(BehaviourReport report) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schema", "renova/behaviour/v1");
        doc.put("status", report.status());
        doc.put("summary", report.summary());
        doc.put("original", report.baselinePlatform());
        doc.put("migrated", report.candidatePlatform());
        List<Map<String, Object>> results = new ArrayList<>();
        for (ScenarioResult r : report.results()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scenario", r.scenario().id());
            m.put("step", r.step() + 1);
            m.put("method", r.method());
            m.put("path", r.path());
            m.put("source", r.scenario().why());
            m.put("same", r.same());
            m.put("differences", r.differences());
            m.put("notes", r.notes());
            m.put("original", exchange(r.baseline()));
            m.put("migrated", exchange(r.candidate()));
            results.add(m);
        }
        doc.put("results", results);
        doc.put("databases", report.databases());
        return doc;
    }

    private static Map<String, Object> exchange(Exchange e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", e.status());
        if (e.error() != null) {
            m.put("error", e.error());
        }
        m.put("contentType", e.header("content-type"));
        String text = e.text();
        m.put("body", text.length() > MAX_BODY_IN_REPORT ? text.substring(0, MAX_BODY_IN_REPORT) + "…" : text);
        return m;
    }
}
