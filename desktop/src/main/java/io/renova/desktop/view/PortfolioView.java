package io.renova.desktop.view;

import atlantafx.base.controls.Message;
import atlantafx.base.theme.Styles;
import io.renova.core.engine.Portfolio;
import io.renova.desktop.Navigator;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Every project under one folder, assessed and ranked: how much work a whole estate of legacy systems is, and
 * where to start. Nothing is modified.
 */
public final class PortfolioView {

    private final Navigator nav;
    private final Portfolio.Result last;
    private final Consumer<Portfolio.Result> remember;
    private final VBox page = Ui.page();

    /**
     * @param last     the portfolio assessed earlier in this session, shown again when coming back; may be null
     * @param remember keeps a new result for that
     */
    public PortfolioView(Navigator nav, Portfolio.Result last, Consumer<Portfolio.Result> remember) {
        this.nav = nav;
        this.last = last;
        this.remember = remember;
    }

    public Node build() {
        if (last == null) {
            page.getChildren().setAll(header(), Ui.section("Assess a folder of projects", null,
                    Ui.empty(Icons.CHART, "No folder assessed yet", "Choose a folder that holds several projects, at any depth. "
                            + "Renova assesses each one against the target that fits it and ranks them, easiest first. "
                            + "Nothing is changed.", chooseButton("Choose folder…", true))));
        } else {
            show(last);
        }
        return page;
    }

    /** Assesses {@code folder} straight away (the --portfolio option). */
    public Node build(Path folder) {
        assess(folder);
        return page;
    }

    private Node header(Node... actions) {
        return Ui.header("Portfolio", "How much work a whole set of legacy systems is, and where to start.", actions);
    }

    private Button chooseButton(String text, boolean accent) {
        Button choose = new Button(text, Icons.of(Icons.FOLDER_OPEN, 16));
        if (accent) {
            choose.getStyleClass().add(Styles.ACCENT);
        }
        choose.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("A folder holding several projects");
            File dir = chooser.showDialog(choose.getScene().getWindow());
            if (dir != null) {
                assess(dir.toPath());
            }
        });
        return choose;
    }

    private void assess(Path folder) {
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(36, 36);
        Label step = Ui.label("Looking for projects in " + folder + "…", Styles.TEXT_MUTED);
        HBox loading = new HBox(12, spinner, step);
        loading.setAlignment(Pos.CENTER_LEFT);
        page.getChildren().setAll(header(), loading);
        Task<Portfolio.Result> task = new Task<>() {
            @Override
            protected Portfolio.Result call() throws Exception {
                return new Portfolio(nav.engine().registry()).assess(folder, this::updateMessage);
            }
        };
        task.messageProperty().addListener((obs, old, message) -> step.setText(message + "…"));
        task.setOnSucceeded(e -> {
            remember.accept(task.getValue());
            show(task.getValue());
        });
        task.setOnFailed(e -> page.getChildren().setAll(header(chooseButton("Choose folder…", true)),
                new Message("This folder could not be assessed", String.valueOf(task.getException().getMessage()))));
        Thread thread = new Thread(task, "renova-portfolio");
        thread.setDaemon(true);
        thread.start();
    }

    private void show(Portfolio.Result r) {
        MenuButton export = new MenuButton("Export", Icons.of(Icons.DOWNLOAD, 14));
        MenuItem csv = new MenuItem("As a spreadsheet (CSV)…");
        csv.setOnAction(e -> save(r, true));
        MenuItem markdown = new MenuItem("As Markdown…");
        markdown.setOnAction(e -> save(r, false));
        export.getItems().setAll(csv, markdown);

        if (r.entries().isEmpty()) {
            page.getChildren().setAll(header(chooseButton("Choose another folder…", true)),
                    Ui.section(r.root().toString(), null, Ui.empty(Icons.FOLDER_OPEN, "No projects found here",
                            "Renova looks up to four folders down for a build file it recognises (pom.xml, build.gradle).")));
            return;
        }
        long assessed = r.entries().stream().filter(e -> e.error() == null).count();
        HBox stats = new HBox(14,
                Ui.stat("Projects", String.valueOf(r.entries().size()), assessed == r.entries().size() ? "All assessed"
                        : (r.entries().size() - assessed) + " could not be assessed"),
                Ui.stat("Findings", String.valueOf(r.findings()), "Places in the code a rule matched"),
                Ui.stat("Automated", Ui.percent(r.automationRate()), "Of all findings, needing no human decision"),
                Ui.stat("Fully automatic", r.fullyAutomatic() + " of " + assessed, "Projects that migrate with recipes and rules alone"));

        TableView<Portfolio.Entry> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<Portfolio.Entry, String> project = text("Project", 220, Portfolio.Entry::path);
        TableColumn<Portfolio.Entry, String> target = text("Suggested target", 300, e -> e.error() == null ? e.target() : "Could not be assessed: " + e.error());
        TableColumn<Portfolio.Entry, Number> findings = number("Findings", Portfolio.Entry::findings);
        TableColumn<Portfolio.Entry, String> automated = text("Automated", 96, e -> e.error() == null ? Ui.percent(e.automationRate()) : "");
        TableColumn<Portfolio.Entry, Number> blockers = number("Blockers", Portfolio.Entry::blockers);
        TableColumn<Portfolio.Entry, Number> ai = number("AI steps", Portfolio.Entry::aiSteps);
        TableColumn<Portfolio.Entry, Number> person = number("Person steps", Portfolio.Entry::manualSteps);
        TableColumn<Portfolio.Entry, String> ready = text("Readiness", 150, e -> e.error() != null ? "error" : e.fullyAutomatic() ? "auto"
                : e.manualSteps() > 0 ? "person" : "ai");
        ready.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String state, boolean empty) {
                super.updateItem(state, empty);
                setText(null);
                setGraphic(empty || state == null ? null : switch (state) {
                    case "auto" -> Ui.badge("Fully automatic", Ui.Tone.GOOD, Icons.CHECK);
                    case "ai" -> Ui.badge("Needs AI", Ui.Tone.INFO, Icons.SPARK);
                    case "person" -> Ui.badge("Needs a person", Ui.Tone.WARN, Icons.USER);
                    default -> Ui.badge("Not assessed", Ui.Tone.MUTED, Icons.MINUS);
                });
            }
        });
        table.getColumns().setAll(List.of(project, target, ready, findings, automated, blockers, ai, person));
        table.getItems().setAll(r.entries());
        table.setFixedCellSize(40);
        table.setPrefHeight(Math.max(4, Math.min(18, r.entries().size() + 1)) * 40 + 8);
        table.setRowFactory(t -> {
            TableRow<Portfolio.Entry> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (!row.isEmpty() && e.getClickCount() == 2 && row.getItem().error() == null) {
                    nav.openProject(r.root().resolve(row.getItem().path()));
                }
            });
            return row;
        });

        page.getChildren().setAll(header(chooseButton("Choose another folder…", false), export), stats,
                Ui.section(r.root().toString(), "Easiest first. Double-click a project to open its assessment and migrate it.", table));
        nav.snapshot("portfolio", 0.8);
    }

    private void save(Portfolio.Result r, boolean csv) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export the portfolio");
        chooser.setInitialFileName(r.root().getFileName() + "-portfolio" + (csv ? ".csv" : ".md"));
        File file = chooser.showSaveDialog(page.getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            Files.writeString(file.toPath(), csv ? Portfolio.csv(r) : Portfolio.markdown(r));
            Message done = new Message("Exported", file.toString());
            done.getStyleClass().add(Styles.SUCCESS);
            done.setOnClose(e -> page.getChildren().remove(done));
            page.getChildren().add(1, done);
        } catch (Exception e) {
            page.getChildren().add(1, new Message("Could not export", String.valueOf(e.getMessage())));
        }
    }

    private static TableColumn<Portfolio.Entry, String> text(String title, double width, Function<Portfolio.Entry, String> value) {
        TableColumn<Portfolio.Entry, String> c = new TableColumn<>(title);
        c.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        c.setPrefWidth(width);
        return c;
    }

    private static TableColumn<Portfolio.Entry, Number> number(String title, Function<Portfolio.Entry, Integer> value) {
        TableColumn<Portfolio.Entry, Number> c = new TableColumn<>(title);
        c.setCellValueFactory(cell -> new SimpleObjectProperty<>(value.apply(cell.getValue())));
        c.setMinWidth(96);
        c.setMaxWidth(120);
        return c;
    }
}
