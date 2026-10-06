package io.renova.intellij;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.vfs.VirtualFile;
import io.renova.core.behaviour.BehaviourReport;
import io.renova.core.engine.AnalysisResult;
import io.renova.core.engine.Analyzer;
import io.renova.core.engine.MigrationOptions;
import io.renova.core.engine.MigrationOutcome;
import io.renova.core.engine.MigrationPlan;
import io.renova.core.engine.Migrator;
import io.renova.core.engine.Planner;
import io.renova.core.engine.PluginRegistry;
import io.renova.core.model.Finding;
import io.renova.core.playbook.Playbook;
import io.renova.core.report.JsonReport;
import io.renova.core.report.MarkdownReport;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Renova for one open project: its latest assessment (shown in the tool window and as editor highlights) and
 * the migrations started from it. The engine runs in the IDE's process; only AI requests leave the machine.
 */
@Service(Service.Level.PROJECT)
public final class RenovaProjectService {

    /** The project's analysis and plan. */
    public record Assessment(Path root, Playbook playbook, AnalysisResult analysis, MigrationPlan plan,
                             Map<String, List<Finding>> findingsByFile) {
    }

    /** A migration started in this IDE session. */
    public static final class Run {
        public enum State { RUNNING, PASSED, FAILED, ERROR }

        public final MigrationOptions options;
        public final List<String> log = new CopyOnWriteArrayList<>();
        public volatile State state = State.RUNNING;
        public volatile MigrationOutcome outcome;
        public volatile String error;

        Run(MigrationOptions options) {
            this.options = options;
        }

        public Path workspace() {
            return options.outputDir();
        }
    }

    private final Project project;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final List<Run> runs = new CopyOnWriteArrayList<>();
    private volatile PluginRegistry registry;
    private volatile Assessment assessment;
    private volatile String assessmentError;
    private volatile boolean assessing;

    public RenovaProjectService(Project project) {
        this.project = project;
    }

    public static RenovaProjectService of(Project project) {
        return project.getService(RenovaProjectService.class);
    }

    public PluginRegistry registry() {
        if (registry == null) {
            synchronized (this) {
                if (registry == null) {
                    registry = PluginRegistry.load();
                }
            }
        }
        return registry;
    }

    public Path root() {
        VirtualFile dir = ProjectUtil.guessProjectDir(project);
        return dir == null ? null : dir.toNioPath();
    }

    public Assessment assessment() {
        return assessment;
    }

    public String assessmentError() {
        return assessmentError;
    }

    public boolean assessing() {
        return assessing;
    }

    public List<Run> runs() {
        return runs;
    }

    /** Called on the UI thread whenever the assessment or a run changes. */
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private void changed() {
        ApplicationManager.getApplication().invokeLater(() -> listeners.forEach(Runnable::run), project.getDisposed());
    }

    /** Analyses the project in the background, then refreshes the tool window and editor highlights. */
    public void assess() {
        Path root = root();
        if (root == null || assessing) {
            return;
        }
        assessing = true;
        changed();
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Renova: assessing " + project.getName(), true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try {
                    assessment = compute(registry(), root);
                    assessmentError = null;
                } catch (Exception e) {
                    assessmentError = e.getMessage() == null ? e.toString() : e.getMessage();
                }
            }

            @Override
            public void onFinished() {
                assessing = false;
                changed();
                DaemonCodeAnalyzer.getInstance(project).restart();
            }
        });
    }

    /** Analyses and plans the project at {@code root}. */
    static Assessment compute(PluginRegistry registry, Path root) throws Exception {
        Playbook playbook = registry.defaultPlaybook(root);
        AnalysisResult analysis = new Analyzer(registry).analyze(root, playbook);
        Map<String, List<Finding>> byFile = analysis.findings().stream()
                .filter(f -> f.file() != null).collect(Collectors.groupingBy(Finding::file));
        return new Assessment(root, playbook, analysis, new Planner().plan(analysis), byFile);
    }

    /** For tests: as if an assessment had just finished. */
    void useAssessment(Assessment a) {
        assessment = a;
        changed();
    }

    /** Migrates a copy of the project into {@code options.outputDir()} in the background, with IDE progress. */
    public Run migrate(Assessment a, MigrationOptions options) {
        Run run = new Run(options);
        runs.addFirst(run);
        changed();
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Renova: migrating " + project.getName(), true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try {
                    run.log.add("Plan: " + a.plan().steps().size() + " steps for " + a.analysis().findings().size() + " findings");
                    MigrationOutcome outcome = new Migrator(registry(), line -> {
                        run.log.add(line);
                        indicator.setText2(line);
                        changed();
                    }).migrate(a.analysis(), a.plan(), options);
                    Path reports = outcome.workspace().resolve(".renova");
                    Files.writeString(reports.resolve("report.md"), MarkdownReport.render(a.analysis(), a.plan(), outcome));
                    Files.writeString(reports.resolve("report.json"), JsonReport.render(a.analysis(), a.plan(), outcome));
                    run.outcome = outcome;
                    run.state = passed(outcome) ? Run.State.PASSED : Run.State.FAILED;
                } catch (Exception e) {
                    run.error = e.getMessage() == null ? e.toString() : e.getMessage();
                    run.log.add("Error: " + run.error);
                    run.state = Run.State.ERROR;
                }
            }

            @Override
            public void onFinished() {
                changed();
                announce(run);
            }
        });
        return run;
    }

    static boolean passed(MigrationOutcome outcome) {
        boolean build = outcome.verification() == null || outcome.verification().success();
        BehaviourReport b = outcome.behaviour();
        return build && (b == null || b.status() == BehaviourReport.Status.SAME || b.status() == BehaviourReport.Status.SKIPPED);
    }

    private void announce(Run run) {
        String title = switch (run.state) {
            case PASSED -> "Renova: migration passed";
            case FAILED -> "Renova: migration finished with problems";
            default -> "Renova: migration stopped";
        };
        List<String> lines = new ArrayList<>();
        if (run.outcome != null) {
            var v = run.outcome.verification();
            lines.add(v == null ? "Not built" : v.success() ? "Build and tests pass" : "Build fails (" + v.errors().size() + " errors)");
            if (run.outcome.behaviour() != null) {
                lines.add("Behaviour: " + run.outcome.behaviour().summary());
            }
            if (!run.outcome.manualSteps().isEmpty()) {
                lines.add(run.outcome.manualSteps().size() + " step(s) for a person");
            }
        } else if (run.error != null) {
            lines.add(run.error);
        }
        var notification = NotificationGroupManager.getInstance().getNotificationGroup("Renova")
                .createNotification(title, String.join("<br>", lines),
                        run.state == Run.State.PASSED ? NotificationType.INFORMATION : NotificationType.WARNING);
        if (run.outcome != null) {
            notification.addAction(NotificationAction.createSimple("Open report", () -> RenovaUi.openReport(project, run)));
            notification.addAction(NotificationAction.createSimple("Open migrated copy", () -> RenovaUi.openWorkspace(project, run)));
        }
        notification.notify(project);
    }
}
