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
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import io.renova.core.behaviour.BehaviourVerifier;
import io.renova.core.model.Finding;
import io.renova.core.playbook.Playbook;
import javafx.stage.FileChooser;
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
    /** A bundled playbook id or a playbook file; null for the one that applies. */
    private String playbookRef;

    public ProjectView(Navigator nav, Engine engine, AiPreferences ai, Path path) {
        this.nav = nav;
        this.engine = engine;
        this.ai = ai;
        this.path = path.toAbsolutePath().normalize();
        this.page = Ui.page();
    }

    public Node build() {
        assess();
        return page;
    }

    private void assess() {
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(40, 40);
        HBox loading = new HBox(12, spinner, Ui.label("Analysing " + path + "…", Styles.TEXT_MUTED));
        loading.setAlignment(Pos.CENTER_LEFT);
        page.getChildren().setAll(Ui.header(Icons.FOLDER, path.getFileName().toString(), path.toString()), loading);

        Task<Engine.Assessment> task = new Task<>() {
            @Override
            protected Engine.Assessment call() throws Exception {
                return engine.assess(path, playbookRef);
            }
        };
        task.setOnSucceeded(e -> show(task.getValue()));
        task.setOnFailed(e -> {
            Button back = new Button("Use the playbook that applies");
            back.setOnAction(x -> {
                playbookRef = null;
                assess();
            });
            page.getChildren().setAll(Ui.header(Icons.FOLDER, path.getFileName().toString(), path.toString(), back),
                    new Message("Renova cannot assess this folder", String.valueOf(task.getException().getMessage())));
        });
        Thread thread = new Thread(task, "renova-assess");
        thread.setDaemon(true);
        thread.start();
    }

    private void show(Engine.Assessment a) {
        Button migrate = new Button("Migrate…", Icons.of(Icons.PLAY, 14));
        migrate.getStyleClass().add(Styles.ACCENT);
        migrate.setOnAction(e -> migrateDialog(a));

        List<PlanStep> plan = a.plan().steps();
        int findings = a.analysis().findings().size();
        // Findings and steps by who resolves them: rules and recipes, AI checked by the build, or a person.
        int[] byResolver = new int[3];
        int[] stepsByResolver = new int[3];
        for (PlanStep step : plan) {
            int r = step.strategy().equals("ai") ? 1 : step.strategy().equals("manual") ? 2 : 0;
            byResolver[r] += step.occurrences();
            stepsByResolver[r]++;
        }
        long blockers = plan.stream().filter(s -> s.rule().severity().name().equals("BLOCKER")).count();
        HBox stats = new HBox(14,
                Ui.stat("Findings", String.valueOf(findings), "Places in the code a rule matched"),
                Ui.stat("Plan steps", String.valueOf(plan.size()), stepsByResolver[0] + " automatic · " + stepsByResolver[1] + " AI · "
                        + stepsByResolver[2] + " for a person"),
                Ui.stat("Blockers", String.valueOf(blockers), "Steps the migration cannot succeed without"),
                Ui.stat("For a person", String.valueOf(stepsByResolver[2]), "Decisions left to you, with guidance"));

        Label lead = Ui.label((byResolver[0] + byResolver[1]) + " of " + findings + " findings are resolved by recipes, rules or AI and "
                + "checked by the real build. " + (byResolver[2] == 0 ? "None are left to a person."
                : byResolver[2] + (byResolver[2] == 1 ? " needs" : " need") + " a person, with guidance."), Styles.TEXT_MUTED);
        VBox split = new VBox(14, lead, Ui.stackedBar(List.of(
                new Ui.Segment(null, "Automatic", byResolver[0], 1),
                new Ui.Segment(null, "AI, checked by the build", byResolver[1], 2),
                new Ui.Segment(null, "A person", byResolver[2], 3))));
        HBox.setHgrow(split, Priority.ALWAYS);
        split.setMinWidth(0);
        HBox automation = new HBox(28, Ui.gauge(a.plan().automationRate(), "automated"), split);
        automation.setAlignment(Pos.CENTER_LEFT);

        Map<Category, Integer> byCategory = new java.util.TreeMap<>(java.util.Comparator.comparing(Category::code));
        a.analysis().findings().forEach(f -> byCategory.merge(f.category(), 1, Integer::sum));
        // A category keeps its colour whatever else is on screen: A is always the first series, B the second.
        List<Ui.Segment> categories = byCategory.entrySet().stream().map(c -> new Ui.Segment(String.valueOf(c.getKey().code()),
                c.getKey().description(), c.getValue(), Math.max(1, Math.min(5, c.getKey().code() - 'A' + 1)))).toList();

        Button reassess = new Button("Re-assess", Icons.of(Icons.REFRESH, 14));
        reassess.setOnAction(e -> assess());
        MenuButton export = new MenuButton("Export", Icons.of(Icons.DOWNLOAD, 14));
        MenuItem markdown = new MenuItem("Assessment as Markdown…");
        markdown.setOnAction(e -> export(a, false));
        MenuItem json = new MenuItem("Assessment as JSON…");
        json.setOnAction(e -> export(a, true));
        export.getItems().setAll(markdown, json);

        TabPane tabs = new TabPane(
                new Tab("Overview", new VBox(18,
                        Ui.section("Automation", "How much of the migration needs no human decision.", automation),
                        Ui.section("Findings by category", "Plan steps run in the order A → E → B → C → D.", Ui.bars(categories)),
                        Ui.section("Playbook", null, playbookInfo(a.playbook())))),
                new Tab("Plan (" + plan.size() + ")", Ui.section("Migration plan", "Select a step to see its guidance and files.", planTable(plan))),
                new Tab("Findings (" + a.analysis().findings().size() + ")", findings(a)));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        for (Tab t : tabs.getTabs()) {
            VBox padded = new VBox(t.getContent());
            padded.setPadding(new Insets(16, 0, 0, 0));
            t.setContent(padded);
        }
        String wanted = nav.startTab();
        tabs.getTabs().stream().filter(t -> wanted != null && t.getText().toLowerCase().startsWith(wanted.toLowerCase()))
                .findFirst().ifPresent(t -> tabs.getSelectionModel().select(t));

        page.getChildren().setAll(
                Ui.header(Icons.FOLDER, path.getFileName().toString(), path.toString(), playbookChooser(a), reassess, export, migrate),
                stats, tabs);
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
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<PlanStep, Number> order = new TableColumn<>("#");
        order.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().order()));
        order.setMaxWidth(50);
        TableColumn<PlanStep, String> title = new TableColumn<>("Step");
        title.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().rule().title()
                + (c.getValue().rule().severity().name().equals("BLOCKER") ? "   · blocker" : "")));
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
                        }, switch (strategy) {
                            case "ai" -> Icons.SHIELD;
                            case "manual" -> Icons.USER;
                            default -> Icons.CHECK;
                        }));
            }
        });
        TableColumn<PlanStep, Number> places = new TableColumn<>("Places");
        places.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().occurrences()));
        places.setMaxWidth(80);
        table.getColumns().setAll(List.of(order, title, category, by, places));
        table.getItems().setAll(plan);
        table.setFixedCellSize(38);
        table.setPrefHeight(Math.min(14, plan.size() + 1) * 38 + 8);

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

        Path defaultScenarios = path.resolve("renova-scenarios.yaml");
        TextField scenarios = fileField(java.nio.file.Files.isRegularFile(defaultScenarios) ? defaultScenarios.toString() : "",
                "renova-scenarios.yaml in the project, if present");
        scenarios.disableProperty().bind(behaviour.selectedProperty().not());
        ToggleSwitch repairBehaviour = toggle(true);
        repairBehaviour.disableProperty().bind(behaviour.selectedProperty().not().or(useAi.selectedProperty().not()));
        TextField settings = fileField("", "Maven's own settings");
        ToggleSwitch offline = toggle(false);
        GridPane advanced = new GridPane(16, 12);
        int ar = 0;
        advanced.add(option("Scenario file", "Requests and multi-step flows to compare, with accepted changes and database checks."), 0, ar);
        advanced.add(browseRow(scenarios, "Scenario file", "*.yaml", "*.yml"), 0, ++ar, 2, 1);
        advanced.addRow(++ar, option("Repair behaviour differences with AI", "Off: report differences without changing code for them."), repairBehaviour);
        advanced.add(option("Maven settings.xml", "For a private repository such as Nexus or Artifactory."), 0, ++ar);
        advanced.add(browseRow(settings, "Maven settings", "*.xml"), 0, ++ar, 2, 1);
        advanced.addRow(++ar, option("Offline builds", "Use only the local Maven repository (mvn -o)."), offline);
        TitledPane more = new TitledPane("Advanced", advanced);
        more.setExpanded(false);
        more.setAnimated(false);
        form.add(more, 0, ++r, 2, 1);

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
                if (!scenarios.getText().isBlank()) {
                    tools.put(BehaviourVerifier.SCENARIOS_OPTION, scenarios.getText().strip());
                }
                if (!repairBehaviour.isSelected()) {
                    tools.put(Migrator.REPAIR_BEHAVIOUR, "false");
                }
            }
            if (!settings.getText().isBlank()) {
                tools.put("maven.settings", settings.getText().strip());
            }
            if (offline.isSelected()) {
                tools.put("maven.offline", "true");
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
                    if (run.cancelRequested()) {
                        run.cancelled();
                    } else {
                        run.fail(ex.getMessage() == null ? ex.toString() : ex.getMessage());
                    }
                }
            });
        }
    }

    static boolean passed(MigrationOutcome outcome) {
        boolean build = outcome.verification() == null || outcome.verification().success();
        BehaviourReport b = outcome.behaviour();
        return build && (b == null || b.status() == BehaviourReport.Status.SAME || b.status() == BehaviourReport.Status.SKIPPED);
    }

    private Node playbookChooser(Engine.Assessment a) {
        ComboBox<String> choice = new ComboBox<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (Playbook p : engine.playbooks()) {
            labels.put(p.id(), p.name());
        }
        if (!labels.containsKey(a.playbook().id())) {
            labels.put(playbookRef == null ? a.playbook().id() : playbookRef, a.playbook().name() + " (file)");
        }
        String other = "\u0000other";
        labels.put(other, "Other playbook file…");
        choice.getItems().setAll(labels.keySet());
        choice.setValue(labels.containsKey(a.playbook().id()) ? a.playbook().id() : playbookRef);
        choice.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(String id) {
                return id == null ? "" : labels.getOrDefault(id, id);
            }

            @Override
            public String fromString(String label) {
                return label;
            }
        });
        choice.setTooltip(new javafx.scene.control.Tooltip("The playbook: the rules, guards and migration notes used"));
        choice.valueProperty().addListener((obs, old, now) -> {
            if (now == null || now.equals(old)) {
                return;
            }
            if (now.equals(other)) {
                FileChooser chooser = new FileChooser();
                chooser.setTitle("A Renova playbook (YAML)");
                chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Playbooks", "*.yaml", "*.yml"));
                File file = chooser.showOpenDialog(choice.getScene().getWindow());
                if (file == null) {
                    javafx.application.Platform.runLater(() -> choice.setValue(old));
                    return;
                }
                playbookRef = file.getAbsolutePath();
            } else {
                playbookRef = now;
            }
            assess();
        });
        return choice;
    }

    private static Node playbookInfo(Playbook p) {
        long guards = p.rules().stream().filter(io.renova.core.playbook.Rule::guard).count();
        VBox box = new VBox(6, Ui.label(p.name(), Styles.TEXT_BOLD), Ui.label(p.id() + " · v" + p.version(), Styles.TEXT_MUTED, Styles.TEXT_SMALL, "mono"));
        if (p.description() != null) {
            box.getChildren().add(Ui.label(p.description().strip(), Styles.TEXT_MUTED));
        }
        HBox counts = new HBox(8, Ui.badge((p.rules().size() - guards) + " rules", Ui.Tone.MUTED), Ui.badge(guards + " guards", Ui.Tone.MUTED),
                Ui.badge(p.knowledge().size() + " migration notes", Ui.Tone.MUTED));
        p.targets().forEach((name, value) -> counts.getChildren().add(Ui.badge(name + " → " + value, Ui.Tone.INFO)));
        box.getChildren().add(new javafx.scene.layout.FlowPane(8, 8, counts.getChildren().toArray(Node[]::new)));
        return box;
    }

    /** Every finding, filtered by category, how it is resolved, and text in the rule, file or code. */
    private Node findings(Engine.Assessment a) {
        Map<String, String> strategyByRule = new java.util.HashMap<>();
        a.plan().steps().forEach(s -> strategyByRule.put(s.rule().id(), s.strategy()));
        List<Finding> all = a.analysis().findings();

        ComboBox<String> category = new ComboBox<>();
        category.getItems().add("All categories");
        all.stream().map(f -> f.category().code() + " · " + f.category().description()).distinct().sorted().forEach(category.getItems()::add);
        category.setValue("All categories");
        ComboBox<String> resolver = new ComboBox<>();
        resolver.getItems().add("Any resolver");
        strategyByRule.values().stream().distinct().sorted().map(st -> STRATEGIES.getOrDefault(st, st)).forEach(resolver.getItems()::add);
        resolver.setValue("Any resolver");
        TextField text = new TextField();
        text.setPromptText("Search rule, file or code");
        HBox.setHgrow(text, Priority.ALWAYS);
        Label count = Ui.label("", Styles.TEXT_MUTED);

        TableView<Finding> table = new TableView<>();
        table.getStyleClass().addAll(Styles.STRIPED, Styles.DENSE);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<Finding, String> cat = new TableColumn<>("Cat");
        cat.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().category().code())));
        cat.setMinWidth(56);
        cat.setMaxWidth(70);
        TableColumn<Finding, String> rule = new TableColumn<>("Rule");
        rule.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().title()));
        rule.setMinWidth(240);
        TableColumn<Finding, String> by = new TableColumn<>("Resolved by");
        by.setCellValueFactory(c -> new SimpleStringProperty(STRATEGIES.getOrDefault(strategyByRule.get(c.getValue().ruleId()),
                String.valueOf(strategyByRule.get(c.getValue().ruleId())))));
        by.setMinWidth(110);
        TableColumn<Finding, String> where = new TableColumn<>("File");
        where.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().file() + (c.getValue().line() > 0 ? ":" + c.getValue().line() : "")));
        where.setMinWidth(300);
        TableColumn<Finding, String> code = new TableColumn<>("Code");
        code.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().evidence() == null ? "" : c.getValue().evidence().strip()));
        where.getStyleClass().add("mono");
        code.getStyleClass().add("mono");
        table.getColumns().setAll(List.of(cat, rule, by, where, code));
        table.setPrefHeight(520);
        table.setRowFactory(t -> {
            javafx.scene.control.TableRow<Finding> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (!row.isEmpty() && e.getClickCount() == 2) {
                    nav.openPath(path.resolve(row.getItem().file()));
                }
            });
            return row;
        });

        Runnable filter = () -> {
            String c = category.getValue();
            String r = resolver.getValue();
            String q = text.getText().strip().toLowerCase();
            List<Finding> shown = all.stream()
                    .filter(f -> c.startsWith("All") || c.startsWith(f.category().code() + " "))
                    .filter(f -> r.startsWith("Any") || r.equals(STRATEGIES.getOrDefault(strategyByRule.get(f.ruleId()), strategyByRule.get(f.ruleId()))))
                    .filter(f -> q.isEmpty() || (f.title() + " " + f.ruleId() + " " + f.file() + " " + f.evidence()).toLowerCase().contains(q))
                    .toList();
            table.getItems().setAll(shown);
            count.setText(shown.size() == all.size() ? all.size() + " findings" : shown.size() + " of " + all.size() + " findings");
        };
        category.valueProperty().addListener((o, x, y) -> filter.run());
        resolver.valueProperty().addListener((o, x, y) -> filter.run());
        text.textProperty().addListener((o, x, y) -> filter.run());
        filter.run();
        HBox filters = new HBox(8, category, resolver, text, count);
        filters.setAlignment(Pos.CENTER_LEFT);
        return Ui.section("Findings", "Every place in the code a rule matched. Double-click to open the file.", new VBox(10, filters, table));
    }

    private void export(Engine.Assessment a, boolean json) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export the assessment");
        chooser.setInitialFileName(path.getFileName() + "-assessment" + (json ? ".json" : ".md"));
        chooser.getExtensionFilters().add(json ? new FileChooser.ExtensionFilter("JSON", "*.json")
                : new FileChooser.ExtensionFilter("Markdown", "*.md"));
        File file = chooser.showSaveDialog(page.getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            java.nio.file.Files.writeString(file.toPath(), engine.export(a, json));
            Message done = new Message("Exported", file.toString());
            done.getStyleClass().add(Styles.SUCCESS);
            done.setOnClose(e -> page.getChildren().remove(done));
            page.getChildren().add(1, done);
        } catch (Exception e) {
            page.getChildren().add(1, new Message("Could not export", String.valueOf(e.getMessage())));
        }
    }

    private static TextField fileField(String value, String prompt) {
        TextField field = new TextField(value);
        field.setPromptText(prompt);
        field.getStyleClass().add("mono");
        HBox.setHgrow(field, Priority.ALWAYS);
        return field;
    }

    private static Node browseRow(TextField field, String title, String... patterns) {
        Button browse = new Button("Browse…");
        browse.disableProperty().bind(field.disableProperty());
        browse.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(title);
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(title, patterns));
            File file = chooser.showOpenDialog(browse.getScene().getWindow());
            if (file != null) {
                field.setText(file.getAbsolutePath());
            }
        });
        return new HBox(8, field, browse);
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
