package io.renova.web.migration;

import io.renova.core.ai.AiSettings;
import io.renova.core.behaviour.BehaviourReport;
import io.renova.core.engine.AnalysisResult;
import io.renova.core.engine.Analyzer;
import io.renova.core.engine.MigrationOptions;
import io.renova.core.engine.MigrationOutcome;
import io.renova.core.engine.MigrationPlan;
import io.renova.core.engine.Migrator;
import io.renova.core.engine.Planner;
import io.renova.core.engine.PluginRegistry;
import io.renova.core.playbook.Playbook;
import io.renova.core.rag.RagSettings;
import io.renova.core.report.JsonReport;
import io.renova.core.report.MarkdownReport;
import io.renova.web.settings.AiSettingsService;
import io.renova.web.store.DataStore;
import io.renova.web.store.MigrationRecord;
import io.renova.web.store.Project;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs migrations as background jobs, a limited number at a time, recording progress as it happens.
 * Each job migrates a copy of the project into its own workspace under the data directory and writes
 * the same reports as the CLI.
 */
@Service
public class MigrationService {

    private final PluginRegistry registry;
    private final DataStore store;
    private final AiSettingsService ai;
    private final ExecutorService executor;

    public MigrationService(PluginRegistry registry, DataStore store, AiSettingsService ai,
                            @Value("${renova.parallel-migrations:1}") int parallel) {
        this.registry = registry;
        this.store = store;
        this.ai = ai;
        this.executor = Executors.newFixedThreadPool(Math.max(1, parallel));
        // Jobs that were running when the server stopped did not finish.
        for (MigrationRecord r : store.migrations()) {
            if (r.status() == MigrationRecord.Status.RUNNING || r.status() == MigrationRecord.Status.QUEUED) {
                store.saveMigration(r.with(MigrationRecord.Status.ERROR, null, now(), null, "The server stopped before the migration finished"));
            }
        }
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    /** The assessment of a project: findings, plan and automation rate, as the JSON report. */
    public String assess(Project project, String playbookRef) throws Exception {
        Playbook playbook = playbook(project, playbookRef);
        AnalysisResult analysis = new Analyzer(registry).analyze(Path.of(project.path()), playbook);
        return JsonReport.render(analysis, new Planner().plan(analysis), null);
    }

    public MigrationRecord start(Project project, String playbookRef, MigrationRecord.Options options, String userId)
            throws Exception {
        if (options.ai() && ai.aiSettings(project.organisationId()).equals(AiSettings.NONE)) {
            throw new IllegalArgumentException("No AI provider is configured for this organisation. An admin can choose one "
                    + "and add your organisation's key in Settings, or start the migration without AI.");
        }
        Playbook playbook = playbook(project, playbookRef);
        String id = LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-"
                + UUID.randomUUID().toString().substring(0, 6);
        MigrationRecord record = new MigrationRecord(id, project.id(), project.name(), playbook.id(), options,
                MigrationRecord.Status.QUEUED, now(), null, null, store.workspaceFor(id).toString(), null, null,
                project.organisationId(), userId);
        store.saveMigration(record);
        store.appendProgress(id, "Queued");
        executor.submit(() -> run(record, project, playbook));
        return record;
    }

    private void run(MigrationRecord queued, Project project, Playbook playbook) {
        String id = queued.id();
        MigrationRecord running = queued.with(MigrationRecord.Status.RUNNING, now(), null, null, null);
        store.saveMigration(running);
        try {
            MigrationRecord.Options o = queued.options();
            Map<String, String> tools = new LinkedHashMap<>();
            if (o.skipTests()) {
                tools.put("verify.skipTests", "true");
            }
            if (o.verifyBehaviour()) {
                tools.put(Migrator.VERIFY_BEHAVIOUR, "true");
            }
            String org = project.organisationId();
            RagSettings rag = o.rag() ? new RagSettings(true, ai.ragSettings(org).budget()) : RagSettings.OFF;
            MigrationOptions options = new MigrationOptions(Path.of(queued.workspace()), o.ai() ? ai.aiSettings(org) : AiSettings.NONE,
                    o.maxAiIterations(), true, tools, List.of(), rag);

            AnalysisResult analysis = new Analyzer(registry).analyze(Path.of(project.path()), playbook);
            MigrationPlan plan = new Planner().plan(analysis);
            store.appendProgress(id, "Plan: " + plan.steps().size() + " steps for " + analysis.findings().size() + " findings, "
                    + Math.round(plan.automationRate() * 100) + "% automated");
            MigrationOutcome outcome = new Migrator(registry, line -> store.appendProgress(id, line)).migrate(analysis, plan, options);
            Path reports = outcome.workspace().resolve(".renova");
            Files.writeString(reports.resolve("report.md"), MarkdownReport.render(analysis, plan, outcome));
            Files.writeString(reports.resolve("report.json"), JsonReport.render(analysis, plan, outcome));

            BehaviourReport b = outcome.behaviour();
            String build = outcome.verification() == null ? "NOT_VERIFIED" : outcome.verification().success() ? "PASSES" : "FAILS";
            MigrationRecord.Summary summary = new MigrationRecord.Summary(build,
                    outcome.verification() == null ? 0 : outcome.verification().errors().size(),
                    b == null ? null : b.status().name(), b == null ? null : b.summary(), outcome.repairRounds(),
                    outcome.aiUsage().requests(), outcome.aiUsage().inputTokens(), outcome.aiUsage().outputTokens(),
                    outcome.manualSteps().size(), plan.automationRate(), analysis.findings().size());
            boolean passed = !build.equals("FAILS") && (b == null || b.status() == BehaviourReport.Status.SAME
                    || b.status() == BehaviourReport.Status.SKIPPED);
            store.appendProgress(id, "Finished: build " + build + (b == null ? "" : ", behaviour " + b.status()));
            store.saveMigration(running.with(passed ? MigrationRecord.Status.PASSED : MigrationRecord.Status.FAILED, null, now(),
                    summary, null));
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            store.appendProgress(id, "Error: " + message);
            store.saveMigration(running.with(MigrationRecord.Status.ERROR, null, now(), null, message));
        }
    }

    private Playbook playbook(Project project, String ref) {
        return ref == null || ref.isBlank() ? registry.playbook(project.playbook()) : registry.playbook(ref);
    }

    private static String now() {
        return Instant.now().toString();
    }
}
