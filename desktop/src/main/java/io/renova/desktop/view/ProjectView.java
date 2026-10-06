package io.renova.desktop.view;

import atlantafx.base.controls.Message;
import atlantafx.base.controls.ToggleSwitch;
import atlantafx.base.theme.Styles;
import io.renova.core.ai.AiSettings;
import io.renova.core.behaviour.BehaviourReport;
import io.renova.core.engine.MigrationOptions;
import io.renova.core.engine.MigrationOutcome;
import io.renova.core.engine.Migrator;
import io.renova.core.engine.PlanStep;
import io.renova.core.model.Category;
import io.renova.core.rag.RagSettings;
import io.renova.desktop.Navigator;
import io.renova.core.config.AiPreferences;
import io.renova.desktop.service.Engine;
import io.renova.desktop.service.MigrationRun;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A project's assessment, and where its migrations start. */
public final class ProjectView {

    private static final Map<String, String> STRATEGIES = Map.of(
            "recipe", "Recipe", "replace", "Text rule", "maven", "Build file edit", "ai", "AI", "manual", "Manual");

    private final Navigator nav;
    private final Engine engine;
    private final AiPreferences ai;
    private final Path path;
    private final VBox page;

    public ProjectView(Navigator nav, Engine engine, AiPreferences ai, Path path) {
        this.nav = nav;
        this.engine = engine;
        this.ai = ai;
        this.path = path.toAbsolutePath().normalize();
        this.page = Ui.page();
    }

    public Node build() {
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(40, 40);
        HBox loading = new HBox(12, spinner, Ui.label("Analysing " + path + "…", Styles.TEXT_MUTED));
        loading.setAlignment(Pos.CENTER_LEFT);
        page.getChildren().setAll(Ui.header(path.getFileName().toString(), path.toString()), loading);

        Task<Engine.Assessment> task = new Task<>() {
            @Override
            protected Engine.Assessment call() throws Exception {
                return engine.assess(path);
            }
        };
        task.setOnSucceeded(e -> show(task.getValue()));
        task.setOnFailed(e -> page.getChildren().setAll(Ui.header(path.getFileName().toString(), path.toString()),
                new Message("Renova cannot assess this folder", String.valueOf(task.getException().getMessage()))));
        Thread thread = new Thread(task, "renova-assess");
        thread.setDaemon(true);
        thread.start();
        return page;
    }

    private void show(Engine.Assessment a) {
        Button migrate = new Button("Migrate…");
        migrate.getStyleClass().add(Styles.ACCENT);
        migrate.setOnAction(e -> migrateDialog(a));

        List<PlanStep> plan = a.plan().steps();
        long manual = plan.stream().filter(s -> s.strategy().equals("manual")).count();
        HBox stats = new HBox(12,
                Ui.stat("Findings", String.valueOf(a.analysis().findings().size()), "places in the code"),
                Ui.stat("Automation rate", Ui.percent(a.plan().automationRate()), "no human decision needed"),
                Ui.stat("Plan steps", String.valueOf(plan.size()), a.playbook().id()),
                Ui.stat("For a person", String.valueOf(manual), "steps with guidance"));

        Map<Category, Integer> byCategory = new java.util.TreeMap<>(java.util.Comparator.comparing(Category::code));
        a.analysis().findings().forEach(f -> byCategory.merge(f.category(), 1, Integer::sum));
        int max = byCategory.values().stream().max(Integer::compare).orElse(1);
        GridPane bars = new GridPane(12, 8);
        int row = 0;
        for (Map.Entry<Category, Integer> c : byCategory.entrySet()) {
            ProgressBar bar = new ProgressBar((double) c.getValue() / max);
            bar.setMaxWidth(Double.MAX_VALUE);
            bar.getStyleClass().add("category-bar");
            GridPane.setHgrow(bar, Priority.ALWAYS);
            bars.addRow(row++, Ui.label(String.valueOf(c.getKey().code()), Styles.TEXT_BOLD, "mono"),
                    Ui.label(c.getKey().description(), Styles.TEXT_MUTED), bar, Ui.label(String.valueOf(c.getValue())));
        }

        page.getChildren().setAll(
                Ui.header(path.getFileName().toString(), path + "  ·  " + a.playbook().name(), migrate),
                stats,
                Ui.section("Findings by category", "Plan steps run in the order A → E → B → C → D.", bars),
                Ui.section("Migration plan", "Select a step to see its guidance and files.", planTable(plan)));
        if (!a.analysis().warnings().isEmpty()) {
            page.getChildren().add(new Message("Analysis warnings", String.join("\n", a.analysis().warnings())));
        }
        nav.snapshot("project", 0.8);
        if (nav.autoMigrate()) {
            Path out = Path.of(System.getProperty("user.home"), "Renova", "migrations", path.getFileName() + "-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
            start(a, new MigrationOptions(out, AiSettings.NONE, 0, true, Map.of(), List.of(), RagSettings.OFF));
        }
    }

    private Node planTable(List<PlanStep> plan) {
        TableView<PlanStep> table = new TableView<>();
        table.getStyleClass().addAll(Styles.STRIPED, Styles.DENSE);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<PlanStep, Number> order = new TableColumn<>("#");
        order.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().order()));
        order.setMaxWidth(50);
        TableColumn<PlanStep, String> title = new TableColumn<>("Step");
        title.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().rule().title()
                + (c.getValue().rule().severity().name().equals("BLOCKER") ? "   (blocker)" : "")));
        title.setPrefWidth(520);
        TableColumn<PlanStep, String> category = new TableColumn<>("Category");
        category.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().rule().category().code())));
        category.setMaxWidth(90);
        TableColumn<PlanStep, String> by = new TableColumn<>("Resolved by");
        by.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().strategy()));
        by.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String strategy, boolean empty) {
                super.updateItem(strategy, empty);
                setText(null);
                setGraphic(empty || strategy == null ? null : Ui.badge(STRATEGIES.getOrDefault(strategy, strategy),
                        switch (strategy) {
                            case "ai" -> Ui.Tone.INFO;
                            case "manual" -> Ui.Tone.WARN;
                            default -> Ui.Tone.GOOD;
                        }));
            }
        });
        TableColumn<PlanStep, Number> places = new TableColumn<>("Places");
        places.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().occurrences()));
        places.setMaxWidth(80);
        table.getColumns().setAll(List.of(order, title, category, by, places));
        table.getItems().setAll(plan);
        table.setFixedCellSize(34);
        table.setPrefHeight(Math.min(14, plan.size() + 1) * 34 + 8);

        Label detail = Ui.label("", Styles.TEXT_MUTED);
        detail.setPadding(new Insets(8, 0, 0, 0));
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, step) -> {
            if (step == null) {
                return;
            }
            String hint = step.rule().fix().hint();
            String files = String.join("\n", step.files().stream().limit(10).toList())
                    + (step.files().size() > 10 ? "\n… and " + (step.files().size() - 10) + " more" : "");
            detail.setText((hint == null ? "" : hint.strip() + "\n\n") + files);
        });
        return new VBox(table, detail);
    }

    private void migrateDialog(Engine.Assessment a) {
        AiSettings aiSettings;
        RagSettings ragDefault;
        try {
            aiSettings = ai.aiSettings();
            ragDefault = ai.ragSettings();
        } catch (Exception e) {
            aiSettings = AiSettings.NONE;
            ragDefault = RagSettings.ON;
        }
        boolean aiReady = !aiSettings.equals(AiSettings.NONE) && aiSettings.apiKey() != null;

        ToggleSwitch useAi = toggle(aiReady);
        useAi.setDisable(!aiReady);
        ToggleSwitch rag = toggle(aiReady && ragDefault.enabled());
        rag.disableProperty().bind(useAi.selectedProperty().not());
        ToggleSwitch tests = toggle(true);
        ToggleSwitch behaviour = toggle(false);
        ComboBox<Integer> rounds = new ComboBox<>();
        rounds.getItems().setAll(0, 1, 2, 3, 5);
        rounds.setValue(3);
        rounds.disableProperty().bind(useAi.selectedProperty().not());
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        TextField out = new TextField(Path.of(System.getProperty("user.home"), "Renova", "migrations",
                path.getFileName() + "-" + stamp).toString());
        out.getStyleClass().add("mono");
        HBox.setHgrow(out, Priority.ALWAYS);
        Button browse = new Button("Browse…");
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Where to put the migrated copy (new or empty folder)");
            File dir = chooser.showDialog(browse.getScene().getWindow());
            if (dir != null) {
                out.setText(dir.toPath().resolve(path.getFileName() + "-" + stamp).toString());
            }
        });

        GridPane form = new GridPane(16, 12);
        int r = 0;
        form.addRow(r++, option("Use AI", aiReady ? "Judgement calls and repair, with " + aiSettings.provider() + " on your own key."
                : "No AI provider with a key is set up (Settings). Those steps are then listed for a person."), useAi);
        form.addRow(r++, option("Retrieve context (RAG)", "Related code, tests and migration notes in each AI request."), rag);
        form.addRow(r++, option("Run the project's tests", "Code that compiles can still fail at runtime."), tests);
        form.addRow(r++, option("Verify behaviour", "Run the original and the migrated app side by side in Docker."), behaviour);
        form.addRow(r++, option("AI repair rounds", "For build errors and behaviour differences."), rounds);
        form.add(option("Migrated copy", "Renova never changes the project itself; each stage is a commit here."), 0, r);
        form.add(new HBox(8, out, browse), 0, ++r, 2, 1);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Migrate " + path.getFileName());
        dialog.setHeaderText("Migrate " + path.getFileName());
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().setAll(new ButtonType("Start migration", ButtonType.OK.getButtonData()),
                ButtonType.CANCEL);
        dialog.initOwner(page.getScene().getWindow());
        AiSettings chosenAi = aiSettings;
        dialog.showAndWait().filter(b -> b.getButtonData() == ButtonType.OK.getButtonData()).ifPresent(b -> {
            Map<String, String> tools = new LinkedHashMap<>();
            if (!tests.isSelected()) {
                tools.put("verify.skipTests", "true");
            }
            if (behaviour.isSelected()) {
                tools.put(Migrator.VERIFY_BEHAVIOUR, "true");
            }
            start(a, new MigrationOptions(Path.of(out.getText().strip()),
                    useAi.isSelected() ? chosenAi : AiSettings.NONE, useAi.isSelected() ? rounds.getValue() : 0, true, tools,
                    List.of(), useAi.isSelected() && rag.isSelected() ? RagSettings.ON : RagSettings.OFF));
        });
    }

    private void start(Engine.Assessment a, MigrationOptions options) {
        {
            MigrationRun run = new MigrationRun(path.getFileName().toString(), options);
            nav.startRun(run, () -> {
                try {
                    run.log("Plan: " + a.plan().steps().size() + " steps for " + a.analysis().findings().size()
                            + " findings, " + Ui.percent(a.plan().automationRate()) + " automated");
                    MigrationOutcome outcome = engine.migrate(a, options, run::log);
                    run.finish(outcome, passed(outcome) ? MigrationRun.State.PASSED : MigrationRun.State.FAILED);
                } catch (Exception ex) {
                    run.fail(ex.getMessage() == null ? ex.toString() : ex.getMessage());
                }
            });
        }
    }

    static boolean passed(MigrationOutcome outcome) {
        boolean build = outcome.verification() == null || outcome.verification().success();
        BehaviourReport b = outcome.behaviour();
        return build && (b == null || b.status() == BehaviourReport.Status.SAME || b.status() == BehaviourReport.Status.SKIPPED);
    }

    private static ToggleSwitch toggle(boolean on) {
        ToggleSwitch t = new ToggleSwitch();
        t.setSelected(on);
        return t;
    }

    private static Node option(String title, String description) {
        VBox box = new VBox(2, Ui.label(title, Styles.TEXT_BOLD), Ui.label(description, Styles.TEXT_MUTED, Styles.TEXT_SMALL));
        box.setMaxWidth(440);
        return box;
    }
}
