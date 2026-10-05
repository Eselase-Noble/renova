package io.renova.desktop.view;

import atlantafx.base.controls.Message;
import atlantafx.base.theme.Styles;
import io.renova.core.behaviour.BehaviourReport;
import io.renova.core.behaviour.ScenarioResult;
import io.renova.core.engine.BuildError;
import io.renova.core.engine.MigrationOutcome;
import io.renova.core.engine.PlanStep;
import io.renova.core.engine.StageResult;
import io.renova.core.workspace.WorkspaceHistory;
import io.renova.desktop.Navigator;
import io.renova.desktop.service.MigrationRun;
import javafx.collections.ListChangeListener;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** One migration: live progress while it runs, then its results, behaviour and every stage's changes. */
public final class MigrationView {

    private final Navigator nav;
    private final MigrationRun run;
    private final VBox page = Ui.page();
    private final TabPane tabs = new TabPane();
    private final ListView<String> log = new ListView<>();

    public MigrationView(Navigator nav, MigrationRun run) {
        this.nav = nav;
        this.run = run;
    }

    public Node build() {
        log.setItems(run.log());
        log.getStyleClass().addAll("log", Styles.DENSE);
        log.setPrefHeight(520);
        run.log().addListener((ListChangeListener<String>) c -> log.scrollTo(run.log().size() - 1));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getStyleClass().add(Styles.TABS_FLOATING);
        render();
        run.state().addListener((obs, old, now) -> render());
        return page;
    }

    private void render() {
        boolean done = run.state().get() != MigrationRun.State.RUNNING;
        Button folder = new Button("Open migrated folder");
        folder.setOnAction(e -> nav.openPath(run.workspace()));
        folder.setDisable(!Files.isDirectory(run.workspace()));
        Button report = new Button("Open report");
        report.setOnAction(e -> nav.openPath(run.workspace().resolve(".renova/report.md")));
        report.setDisable(!done || run.outcome() == null);

        HBox status = new HBox(8, stateBadge());
        status.setAlignment(Pos.CENTER_LEFT);
        if (!done) {
            ProgressIndicator spinner = new ProgressIndicator();
            spinner.setMaxSize(18, 18);
            status.getChildren().add(spinner);
        }
        String elapsed = elapsed();
        page.getChildren().setAll(Ui.header("Migration of " + run.projectName(), run.workspace() + "  ·  " + elapsed, folder, report),
                status);

        Tab logTab = new Tab("Log", log);
        if (!done || run.outcome() == null) {
            if (run.error() != null) {
                page.getChildren().add(new Message("The migration stopped", run.error()));
            }
            tabs.getTabs().setAll(logTab);
        } else {
            MigrationOutcome o = run.outcome();
            page.getChildren().add(summary(o));
            tabs.getTabs().setAll(new Tab("Overview", overview(o)));
            if (o.behaviour() != null) {
                tabs.getTabs().add(new Tab("Behaviour", behaviour(o.behaviour())));
            }
            tabs.getTabs().addAll(new Tab("Changes", changes()), logTab);
            String wanted = nav.startTab();
            tabs.getTabs().stream().filter(t -> t.getText().equalsIgnoreCase(String.valueOf(wanted))).findFirst()
                    .ifPresent(t -> tabs.getSelectionModel().select(t));
        }
        page.getChildren().add(tabs);
    }

    private Node stateBadge() {
        return switch (run.state().get()) {
            case RUNNING -> Ui.badge("Running", Ui.Tone.INFO);
            case PASSED -> Ui.badge("Passed", Ui.Tone.GOOD);
            case FAILED -> Ui.badge("Failed", Ui.Tone.BAD);
            case ERROR -> Ui.badge("Error", Ui.Tone.BAD);
        };
    }

    private String elapsed() {
        Instant end = run.finished() == null ? Instant.now() : run.finished();
        long s = Duration.between(run.started(), end).toSeconds();
        return s < 60 ? s + "s" : (s / 60) + "m " + (s % 60) + "s";
    }

    private Node summary(MigrationOutcome o) {
        String build = o.verification() == null ? "Not built" : o.verification().success() ? "Passes" : "Fails";
        String buildHint = o.verification() == null ? null : o.verification().errors().size() + " error(s)";
        BehaviourReport b = o.behaviour();
        String behaviour = b == null ? "Not checked" : switch (b.status()) {
            case SAME -> "Same";
            case DIFFERENT -> "Differs";
            case SKIPPED -> "Not compared";
            case FAILED -> "Comparison failed";
        };
        boolean aiUsed = o.aiUsage().requests() > 0;
        return new HBox(12,
                Ui.stat("Build and tests", build, buildHint),
                Ui.stat("Behaviour", behaviour, b == null ? null : b.summary()),
                Ui.stat("AI", aiUsed ? o.aiUsage().requests() + " requests" : "Not used",
                        aiUsed ? Ui.tokens(o.aiUsage().inputTokens()) + " in · " + Ui.tokens(o.aiUsage().outputTokens()) + " out · "
                                + o.repairRounds() + " repair round(s)" : null),
                Ui.stat("For a person", String.valueOf(o.manualSteps().size()), "steps with guidance"));
    }

    private Node overview(MigrationOutcome o) {
        VBox stages = new VBox(6);
        for (StageResult s : o.stages()) {
            Ui.Tone tone = switch (s.status()) {
                case APPLIED -> Ui.Tone.GOOD;
                case PARTIAL -> Ui.Tone.WARN;
                case FAILED -> Ui.Tone.BAD;
                case SKIPPED -> Ui.Tone.MUTED;
            };
            Label name = Ui.label(s.stage(), Styles.TEXT_BOLD);
            name.setMinWidth(220);
            HBox row = new HBox(12, Ui.badge(s.status().name().toLowerCase(), tone), name, Ui.label(s.summary(), Styles.TEXT_MUTED));
            row.setAlignment(Pos.CENTER_LEFT);
            stages.getChildren().add(row);
        }
        VBox body = new VBox(16, Ui.section("Stages", "Each stage is one commit in the migrated folder; see Changes.", stages));
        if (o.verification() != null && !o.verification().errors().isEmpty()) {
            VBox errors = new VBox(6);
            for (BuildError e : o.verification().errors().stream().limit(40).toList()) {
                errors.getChildren().add(new VBox(2,
                        Ui.label((e.file() == null ? "(build)" : e.file()) + (e.line() > 0 ? ":" + e.line() : ""), "mono", Styles.TEXT_SMALL),
                        Ui.label(e.message(), Styles.TEXT_MUTED)));
            }
            body.getChildren().add(Ui.section("Build errors", null, errors));
        }
        if (!o.manualSteps().isEmpty()) {
            VBox manual = new VBox(10);
            for (PlanStep step : o.manualSteps()) {
                manual.getChildren().add(new VBox(2, Ui.label(step.rule().title(), Styles.TEXT_BOLD),
                        Ui.label(step.rule().fix().hint() == null ? "" : step.rule().fix().hint().strip(), Styles.TEXT_MUTED)));
            }
            body.getChildren().add(Ui.section("For a person", "Decisions Renova leaves to your team, with guidance.", manual));
        }
        return body;
    }

    private Node behaviour(BehaviourReport b) {
        VBox body = new VBox(12, Ui.label(b.summary(), Styles.TEXT_MUTED));
        if (b.baselinePlatform() != null) {
            body.getChildren().add(Ui.label("Original: " + b.baselinePlatform() + "    Migrated: " + b.candidatePlatform(), Styles.TEXT_SMALL));
        }
        for (ScenarioResult r : b.results()) {
            HBox head = new HBox(10, r.same() ? Ui.badge("Same", Ui.Tone.GOOD) : Ui.badge("Different", Ui.Tone.WARN),
                    Ui.label(r.label(), "mono"),
                    Ui.label(status(r.baseline().status()) + " → " + status(r.candidate().status()), Styles.TEXT_MUTED));
            head.setAlignment(Pos.CENTER_LEFT);
            VBox item = new VBox(4, head);
            r.differences().forEach(d -> item.getChildren().add(Ui.label("• " + d, Styles.TEXT_SMALL)));
            if (r.handlerFile() != null && !r.same()) {
                item.getChildren().add(Ui.label("Handled in " + r.handlerFile(), Styles.TEXT_MUTED, Styles.TEXT_SMALL, "mono"));
            }
            body.getChildren().add(item);
        }
        b.databases().forEach((id, diffs) -> body.getChildren().add(new HBox(10,
                diffs.isEmpty() ? Ui.badge("Same rows", Ui.Tone.GOOD) : Ui.badge("Different rows", Ui.Tone.WARN),
                Ui.label(id + (diffs.isEmpty() ? "" : ": " + String.join("; ", diffs)), Styles.TEXT_SMALL))));
        b.accepted().forEach(a -> body.getChildren().add(Ui.label("Accepted change: " + a, Styles.TEXT_MUTED)));
        return body;
    }

    private static String status(int code) {
        return code < 0 ? "no answer" : String.valueOf(code);
    }

    /** The stages as commits on the left, the selected stage's diff on the right. */
    private Node changes() {
        ListView<WorkspaceHistory.Commit> commits = new ListView<>();
        commits.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(WorkspaceHistory.Commit c, boolean empty) {
                super.updateItem(c, empty);
                setText(empty || c == null ? null
                        : c.message().replaceFirst("^renova: ", "") + "\n" + c.filesChanged() + " file(s)  +" + c.insertions() + " −" + c.deletions());
            }
        });
        ListView<String> diff = new ListView<>();
        diff.getStyleClass().addAll("diff", Styles.DENSE);
        diff.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(String line, boolean empty) {
                super.updateItem(line, empty);
                getStyleClass().removeAll("added", "removed", "hunk", "file");
                setText(empty ? null : line);
                if (!empty && line != null) {
                    if (line.startsWith("diff --git")) {
                        getStyleClass().add("file");
                        setText(line.replaceFirst("^diff --git a/\\S+ b/", ""));
                    } else if (line.startsWith("+") && !line.startsWith("+++")) {
                        getStyleClass().add("added");
                    } else if (line.startsWith("-") && !line.startsWith("---")) {
                        getStyleClass().add("removed");
                    } else if (line.startsWith("@@")) {
                        getStyleClass().add("hunk");
                    }
                }
            }
        });
        commits.getSelectionModel().selectedItemProperty().addListener((obs, old, c) -> {
            if (c != null) {
                background(() -> WorkspaceHistory.diff(run.workspace(), c.hash()).lines()
                        .filter(l -> !l.startsWith("index ") && !l.startsWith("--- ") && !l.startsWith("+++ ")).limit(20_000).toList(),
                        lines -> diff.getItems().setAll(lines));
            }
        });
        background(() -> WorkspaceHistory.commits(run.workspace()), list -> {
            commits.getItems().setAll(list.size() > 1 ? list.subList(1, list.size()) : List.of());
            if (!commits.getItems().isEmpty()) {
                commits.getSelectionModel().selectFirst();
            }
        });
        SplitPane split = new SplitPane(commits, diff);
        split.setDividerPositions(0.28);
        split.setPrefHeight(560);
        return split;
    }

    private interface Work<T> {
        T call() throws Exception;
    }

    private static <T> void background(Work<T> work, java.util.function.Consumer<T> then) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(e -> then.accept(task.getValue()));
        Thread thread = new Thread(task, "renova-history");
        thread.setDaemon(true);
        thread.start();
    }
}
