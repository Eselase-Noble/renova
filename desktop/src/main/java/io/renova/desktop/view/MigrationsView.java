package io.renova.desktop.view;

import atlantafx.base.theme.Styles;
import io.renova.desktop.Navigator;
import io.renova.desktop.service.MigrationHistory;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Migrations run from this app, and any migrated copy opened from disk. */
public final class MigrationsView {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault());
    private final Navigator nav;
    private final MigrationHistory history;

    public MigrationsView(Navigator nav, MigrationHistory history) {
        this.nav = nav;
        this.history = history;
    }

    public Node build() {
        Button open = new Button("Open migrated folder…");
        open.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Open a migrated copy (a folder with .renova/report.json)");
            File dir = chooser.showDialog(open.getScene().getWindow());
            if (dir != null) {
                nav.showWorkspace(dir.toPath());
            }
        });

        List<MigrationHistory.Entry> entries = history.list();
        TableView<MigrationHistory.Entry> table = new TableView<>();
        table.getStyleClass().addAll(Styles.STRIPED);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(Ui.label("No migrations yet. Open a project and choose Migrate.", Styles.TEXT_MUTED));
        TableColumn<MigrationHistory.Entry, String> project = column("Project", 180, MigrationHistory.Entry::projectName);
        TableColumn<MigrationHistory.Entry, String> when = column("Started", 160, e -> when(e.startedAt()));
        TableColumn<MigrationHistory.Entry, String> result = column("Result", 110, MigrationHistory.Entry::state);
        result.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String state, boolean empty) {
                super.updateItem(state, empty);
                setText(null);
                setGraphic(empty || state == null ? null : switch (state) {
                    case "PASSED" -> Ui.badge("Passed", Ui.Tone.GOOD);
                    case "FAILED" -> Ui.badge("Failed", Ui.Tone.BAD);
                    default -> Ui.badge("Error", Ui.Tone.BAD);
                });
            }
        });
        TableColumn<MigrationHistory.Entry, String> build = column("Build and tests", 120, MigrationHistory.Entry::build);
        TableColumn<MigrationHistory.Entry, String> behaviour = column("Behaviour", 120, MigrationHistory.Entry::behaviour);
        TableColumn<MigrationHistory.Entry, String> ai = column("AI", 130, e -> e.aiRequests() == 0 ? "Not used"
                : e.aiRequests() + " requests · " + Ui.tokens(e.tokens()));
        TableColumn<MigrationHistory.Entry, String> where = column("Migrated copy", 300, MigrationHistory.Entry::workspace);
        where.getStyleClass().add("mono");
        table.getColumns().setAll(List.of(project, when, result, build, behaviour, ai, where));
        table.getItems().setAll(entries);
        table.setFixedCellSize(36);
        table.setPrefHeight(Math.max(4, Math.min(16, entries.size() + 1)) * 36 + 8);
        table.setRowFactory(t -> {
            TableRow<MigrationHistory.Entry> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (!row.isEmpty() && e.getClickCount() == 2) {
                    nav.showWorkspace(Path.of(row.getItem().workspace()));
                }
            });
            return row;
        });

        Button show = new Button("Show");
        show.getStyleClass().add(Styles.ACCENT);
        Button folder = new Button("Open folder");
        Button forget = new Button("Remove from history");
        forget.getStyleClass().add(Styles.FLAT);
        show.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        folder.disableProperty().bind(show.disableProperty());
        forget.disableProperty().bind(show.disableProperty());
        show.setOnAction(e -> nav.showWorkspace(Path.of(table.getSelectionModel().getSelectedItem().workspace())));
        folder.setOnAction(e -> nav.openPath(Path.of(table.getSelectionModel().getSelectedItem().workspace())));
        forget.setOnAction(e -> nav.forget(Path.of(table.getSelectionModel().getSelectedItem().workspace())));

        return Ui.page(
                Ui.header("Migrations", "Every migration run from this app. Removing one from the history leaves its "
                        + "migrated copy on disk.", open),
                Ui.section("History", "Double-click a migration to see its results, changes, report and AI exchanges.",
                        new javafx.scene.layout.VBox(8, table, new javafx.scene.layout.HBox(8, show, folder, forget))));
    }

    private static TableColumn<MigrationHistory.Entry, String> column(String title, double width,
                                                                       java.util.function.Function<MigrationHistory.Entry, String> value) {
        TableColumn<MigrationHistory.Entry, String> c = new TableColumn<>(title);
        c.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        c.setPrefWidth(width);
        return c;
    }

    private static String when(String instant) {
        try {
            return WHEN.format(Instant.parse(instant));
        } catch (Exception e) {
            return String.valueOf(instant);
        }
    }
}
