package io.renova.desktop.view;

import atlantafx.base.theme.Styles;
import io.renova.core.ai.AiSettings;
import io.renova.core.config.AiPreferences;
import io.renova.desktop.Navigator;
import io.renova.desktop.service.MigrationHistory;
import io.renova.desktop.service.MigrationRun;
import io.renova.desktop.service.RecentProjects;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The start screen: how the migrations run on this machine have gone, what is running now, and the way to the
 * projects and migrations worked on last.
 */
public final class OverviewView {

    private static final int DAYS = 14;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final Navigator nav;
    private final AiPreferences ai;
    private final RecentProjects recent;
    private final MigrationHistory history;
    private final ObservableList<MigrationRun> runs;

    public OverviewView(Navigator nav, AiPreferences ai, RecentProjects recent, MigrationHistory history, ObservableList<MigrationRun> runs) {
        this.nav = nav;
        this.ai = ai;
        this.recent = recent;
        this.history = history;
        this.runs = runs;
    }

    public Node build() {
        List<MigrationHistory.Entry> entries = history.list();
        List<RecentProjects.Entry> projects = recent.list();
        long running = runs.stream().filter(r -> r.state().get() == MigrationRun.State.RUNNING).count();
        long passed = entries.stream().filter(e -> e.state().equals("PASSED")).count();
        long judged = entries.stream().filter(e -> e.state().equals("PASSED") || e.state().equals("FAILED")).count();
        double automation = entries.stream().filter(e -> e.automationRate() != null).mapToDouble(MigrationHistory.Entry::automationRate).average().orElse(-1);
        long tokens = entries.stream().mapToLong(MigrationHistory.Entry::tokens).sum();
        int requests = entries.stream().mapToInt(MigrationHistory.Entry::aiRequests).sum();

        Button open = new Button("Open project folder…", Icons.of(Icons.FOLDER_OPEN, 16));
        open.getStyleClass().add(Styles.ACCENT);
        open.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Open a legacy project");
            File dir = chooser.showDialog(open.getScene().getWindow());
            if (dir != null) {
                nav.openProject(dir.toPath());
            }
        });

        VBox passedCard = Ui.stat("Migrations passed", judged == 0 ? "—" : Ui.percent((double) passed / judged),
                judged == 0 ? "No finished migration yet" : passed + " of " + judged + " finished: build, tests and behaviour");
        if (judged > 0) {
            passedCard.getChildren().add(2, Ui.meter((double) passed / judged));
        }
        VBox automationCard = Ui.stat("Automation rate", automation < 0 ? "—" : Ui.percent(automation),
                automation < 0 ? "Shown after the next migration" : "Average share of work needing no human decision");
        if (automation >= 0) {
            automationCard.getChildren().add(2, Ui.meter(automation));
        }
        HBox stats = new HBox(14,
                Ui.stat("Projects", String.valueOf(projects.size()), running == 0 ? "None being migrated now" : running + " being migrated now"),
                passedCard, automationCard,
                Ui.stat("AI tokens used", Ui.tokens(tokens), requests == 0 ? "On your own key" : requests + " requests on your own key"));

        VBox page = Ui.page(Ui.header("Overview", "Legacy projects assessed and migrated on this machine. Your code and every migrated "
                + "copy stay here; only AI requests leave it, on your own key.", open));
        Node started = gettingStarted(projects, entries);
        if (started != null) {
            page.getChildren().add(started);
        }
        page.getChildren().add(stats);

        VBox activity = Ui.section("Migration activity", "Migrations started in the last " + DAYS + " days, by how they ended.",
                Ui.columns(activity(entries)));
        VBox progress = Ui.section("In progress", null, inProgress());
        GridPane top = twoColumns(activity, progress);

        VBox migrations = Ui.section("Recent migrations", null, recentMigrations(entries));
        flush(migrations);
        VBox recentProjects = Ui.section("Projects", null, recentProjects(projects));
        flush(recentProjects);
        page.getChildren().addAll(top, twoColumns(migrations, recentProjects));
        return page;
    }

    /** Two cards side by side, the first twice as wide. */
    private static GridPane twoColumns(Node wide, Node narrow) {
        GridPane grid = new GridPane(18, 0);
        ColumnConstraints left = new ColumnConstraints();
        left.setPercentWidth(64);
        ColumnConstraints right = new ColumnConstraints();
        right.setPercentWidth(36);
        grid.getColumnConstraints().addAll(left, right);
        grid.add(wide, 0, 0);
        grid.add(narrow, 1, 0);
        GridPane.setVgrow(wide, Priority.ALWAYS);
        GridPane.setVgrow(narrow, Priority.ALWAYS);
        return grid;
    }

    /** Lists run edge to edge inside their card, like table rows. */
    private static void flush(VBox section) {
        section.getChildren().get(1).setStyle("-fx-padding: 0 0 8 0;");
    }

    private static List<Ui.Day> activity(List<MigrationHistory.Entry> entries) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        List<Ui.Day> days = new ArrayList<>();
        for (int i = DAYS - 1; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            int passed = 0;
            int failed = 0;
            int stopped = 0;
            for (MigrationHistory.Entry e : entries) {
                try {
                    if (!Instant.parse(e.startedAt()).atZone(zone).toLocalDate().equals(day)) {
                        continue;
                    }
                } catch (RuntimeException ignored) {
                    continue;
                }
                switch (e.state()) {
                    case "PASSED" -> passed++;
                    case "FAILED" -> failed++;
                    default -> stopped++;
                }
            }
            days.add(new Ui.Day(DAY.format(day), passed, failed, stopped));
        }
        return days;
    }

    private Node inProgress() {
        List<MigrationRun> running = runs.stream().filter(r -> r.state().get() == MigrationRun.State.RUNNING).toList();
        if (running.isEmpty()) {
            VBox none = Ui.empty(Icons.WORKFLOW, "Nothing is running", "Start a migration from a project. It works on a copy and reports here.");
            none.setStyle("-fx-border-color: transparent;");
            return none;
        }
        VBox list = new VBox(4);
        for (MigrationRun run : running) {
            String last = run.log().isEmpty() ? "Starting…" : run.log().getLast().replaceFirst(" to /.*$", "");
            list.getChildren().add(row(run.projectName(), last, Ui.badge("Running", Ui.Tone.INFO, Icons.REFRESH), () -> nav.showRun(run), false));
        }
        return list;
    }

    private Node recentMigrations(List<MigrationHistory.Entry> entries) {
        if (entries.isEmpty()) {
            Label none = Ui.label("No migrations yet. Open a project, review its assessment, then choose Migrate.", Styles.TEXT_MUTED);
            none.setPadding(new Insets(18));
            return none;
        }
        VBox list = new VBox();
        entries.stream().limit(6).forEach(e -> list.getChildren().add(row(e.projectName(),
                e.build().equals("Not built") ? e.workspace() : "Build " + e.build().toLowerCase() + " · behaviour " + e.behaviour().toLowerCase()
                        + (e.automationRate() == null ? "" : " · " + Ui.percent(e.automationRate()) + " automated"),
                switch (e.state()) {
                    case "PASSED" -> Ui.badge("Passed", Ui.Tone.GOOD, Icons.CHECK);
                    case "FAILED" -> Ui.badge("Failed", Ui.Tone.BAD, Icons.CROSS);
                    case "CANCELLED" -> Ui.badge("Cancelled", Ui.Tone.MUTED, Icons.STOP);
                    default -> Ui.badge("Error", Ui.Tone.BAD, Icons.ALERT);
                }, () -> nav.showWorkspace(Path.of(e.workspace())), true)));
        Button all = new Button("All migrations");
        all.getStyleClass().add(Styles.FLAT);
        all.setOnAction(e -> nav.migrations());
        VBox.setMargin(all, new Insets(6, 0, 0, 10));
        list.getChildren().add(all);
        return list;
    }

    private Node recentProjects(List<RecentProjects.Entry> projects) {
        if (projects.isEmpty()) {
            Label none = Ui.label("No project has been opened yet.", Styles.TEXT_MUTED);
            none.setPadding(new Insets(18));
            return none;
        }
        VBox list = new VBox();
        projects.stream().limit(6).forEach(p -> list.getChildren().add(row(p.name(), p.path(), Icons.of(Icons.FOLDER, 16),
                () -> nav.openProject(Path.of(p.path())), true)));
        Button all = new Button("All projects");
        all.getStyleClass().add(Styles.FLAT);
        all.setOnAction(e -> nav.home());
        VBox.setMargin(all, new Insets(6, 0, 0, 10));
        list.getChildren().add(all);
        return list;
    }

    private static Node row(String title, String detail, Node trailing, Runnable open, boolean ruled) {
        Label second = Ui.label(detail, Styles.TEXT_MUTED, Styles.TEXT_SMALL);
        second.setWrapText(false);
        VBox text = new VBox(2, Ui.label(title, Styles.TEXT_BOLD), second);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(12, text, trailing);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("row-item");
        if (!ruled) {
            row.setStyle("-fx-border-color: transparent; -fx-padding: 8 4 8 4;");
        }
        row.setOnMouseClicked(e -> open.run());
        return row;
    }

    /** The steps from a fresh install to a first migration; null once all are done. */
    private Node gettingStarted(List<RecentProjects.Entry> projects, List<MigrationHistory.Entry> entries) {
        boolean aiReady;
        try {
            AiSettings settings = ai.aiSettings();
            aiReady = !settings.equals(AiSettings.NONE) && settings.apiKey() != null;
        } catch (Exception e) {
            aiReady = false;
        }
        record Step(String title, String text, boolean done, String action, Runnable go) {
        }
        List<Step> steps = List.of(
                new Step("Connect an AI provider", "Add your own Anthropic or OpenAI key. Without one, AI steps are listed for a person.",
                        aiReady, "Open settings", nav::settings),
                new Step("Open a project", "Point Renova at a project folder. It is only read.", !projects.isEmpty(), "Projects", nav::home),
                new Step("Run a migration", "Review the assessment, then migrate a copy and follow every stage.",
                        !entries.isEmpty() || !runs.isEmpty(), "Choose a project", nav::home));
        long done = steps.stream().filter(Step::done).count();
        if (done == steps.size()) {
            return null;
        }
        int next = 0;
        while (steps.get(next).done()) {
            next++;
        }
        GridPane grid = new GridPane();
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(100.0 / steps.size());
            grid.getColumnConstraints().add(column);
            Label number = new Label(step.done() ? "" : String.valueOf(i + 1));
            number.getStyleClass().add("check-number");
            if (step.done()) {
                number.getStyleClass().add("done");
                number.setGraphic(Icons.of(Icons.CHECK, 12, "on-colour"));
            } else if (i == next) {
                number.getStyleClass().add("next");
            }
            Label title = Ui.label(step.title(), Styles.TEXT_BOLD);
            if (step.done()) {
                title.getStyleClass().add(Styles.TEXT_MUTED);
            }
            HBox head = new HBox(10, number, title);
            head.setAlignment(Pos.CENTER_LEFT);
            VBox cell = new VBox(head, Ui.label(step.text(), Styles.TEXT_MUTED, Styles.TEXT_SMALL));
            if (!step.done()) {
                Button go = new Button(step.action());
                go.getStyleClass().add(Styles.SMALL);
                if (i == next) {
                    go.getStyleClass().add(Styles.ACCENT);
                }
                go.setOnAction(e -> step.go().run());
                cell.getChildren().add(go);
            }
            cell.getStyleClass().add("check-step");
            if (i == steps.size() - 1) {
                cell.setStyle("-fx-border-color: transparent;");
            }
            grid.add(cell, i, 0);
        }
        VBox section = Ui.section("Get started", done + " of " + steps.size() + " steps done", grid);
        section.getChildren().get(1).setStyle("-fx-padding: 0;");
        return section;
    }
}
