package io.renova.core.behaviour;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.renova.core.engine.MigrationContext;

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
        Optional<String> unsupported = runner.get().unsupported(context.project());
        if (unsupported.isPresent()) {
            return BehaviourReport.skipped(unsupported.get());
        }
        List<Scenario> scenarios = runner.get().discover(context.project(), context.project().root());
        if (scenarios.isEmpty()) {
            return BehaviourReport.skipped("no entry points found to send requests to");
        }
        if (scenarios.size() > MAX_SCENARIOS) {
            scenarios = scenarios.subList(0, MAX_SCENARIOS);
        }
        if (!DockerSandbox.available()) {
            return BehaviourReport.skipped("Docker is not available; behavioural verification runs applications in containers");
        }
        Path workDir = context.workspace().lcDir().resolve("behaviour");
        BehaviourRunner.Deployments deployments = runner.get().prepare(context, workDir, progress);
        DockerSandbox.Run run = DockerSandbox.run(deployments, scenarios, workDir, progress);
        String baselinePlatform = deployments.baseline().platform();
        String candidatePlatform = deployments.candidate().platform();
        if (!run.baselineReady()) {
            return new BehaviourReport(BehaviourReport.Status.FAILED, "the original application did not start on "
                    + baselinePlatform, baselinePlatform, candidatePlatform, List.of(), run.baselineLog(), run.candidateLog());
        }
        if (!run.candidateReady()) {
            return new BehaviourReport(BehaviourReport.Status.DIFFERENT, "the migrated application did not start on "
                    + candidatePlatform, baselinePlatform, candidatePlatform, List.of(), run.baselineLog(), run.candidateLog());
        }
        List<ScenarioResult> results = new ArrayList<>();
        for (Scenario scenario : scenarios) {
            DockerSandbox.Answers answers = run.answers().get(scenario.id());
            results.add(answers == null
                    ? new ScenarioResult(scenario, Exchange.failed("not sent"), Exchange.failed("not sent"), List.of(),
                            List.of("no answers recorded"))
                    : ResponseComparator.compare(scenario, answers.baseline(), answers.baselineAgain(), answers.candidate()));
        }
        long differing = results.stream().filter(r -> !r.same()).count();
        String summary = differing == 0 ? "all " + results.size() + " request(s) answered the same"
                : differing + " of " + results.size() + " request(s) answered differently";
        return new BehaviourReport(differing == 0 ? BehaviourReport.Status.SAME : BehaviourReport.Status.DIFFERENT, summary,
                baselinePlatform, candidatePlatform, results, run.baselineLog(), run.candidateLog());
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
                md.append("### `").append(r.scenario().method()).append(' ').append(r.scenario().path()).append("`\n\n");
                md.append("From ").append(r.scenario().why()).append(".\n\n");
                r.differences().forEach(d -> md.append("- ").append(d).append('\n'));
                md.append('\n');
            }
        }
        if (!report.results().isEmpty()) {
            md.append("## All requests\n\n| Request | Original | Migrated | Result |\n|---|---|---|---|\n");
            for (ScenarioResult r : report.results()) {
                md.append("| `").append(r.scenario().method()).append(' ').append(r.scenario().path()).append("` | ")
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
            m.put("id", r.scenario().id());
            m.put("method", r.scenario().method());
            m.put("path", r.scenario().path());
            m.put("source", r.scenario().why());
            m.put("same", r.same());
            m.put("differences", r.differences());
            m.put("notes", r.notes());
            m.put("original", exchange(r.baseline()));
            m.put("migrated", exchange(r.candidate()));
            results.add(m);
        }
        doc.put("results", results);
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
