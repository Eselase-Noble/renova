package io.renova.desktop.view;

import atlantafx.base.theme.Styles;
import io.renova.desktop.Navigator;
import io.renova.desktop.service.MigrationHistory;
import io.renova.desktop.service.MigrationRun;
import io.renova.desktop.service.RecentProjects;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

/** Open a project folder, or pick one opened before; and the latest migrations, one click away. */
public final class HomeView {

    private static final int RECENT_MIGRATIONS = 5;

    private final Navigator nav;
    private final RecentProjects recent;
    private final MigrationHistory history;
    private final ObservableList<MigrationRun> runs;

    public HomeView(Navigator nav, RecentProjects recent, MigrationHistory history, ObservableList<MigrationRun> runs) {
        this.nav = nav;
        this.recent = recent;
        this.history = history;
        this.runs = runs;
    }

    public Node build() {
        Button open = openButton("Open project folder…");
        open.getStyleClass().add(Styles.ACCENT);
        open.setDefaultButton(true);

        FlowPane tiles = new FlowPane(14, 14);
        for (RecentProjects.Entry entry : recent.list()) {
            StackPane icon = new StackPane(Icons.of(Icons.FOLDER, 18, "accent"));
            icon.getStyleClass().addAll("icon-tile", "tint");
            VBox text = new VBox(2, Ui.label(entry.name(), Styles.TEXT_BOLD), Ui.label(entry.path(), Styles.TEXT_MUTED, Styles.TEXT_SMALL, "mono"));
            text.setMinWidth(0);
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox tile = new HBox(icon, text);
            tile.setAlignment(Pos.CENTER_LEFT);
            tile.getStyleClass().add("recent-tile");
            tile.setPrefWidth(330);
            tile.setOnMouseClicked(e -> nav.openProject(Path.of(entry.path())));
            tiles.getChildren().add(tile);
        }
        Node projects = tiles.getChildren().isEmpty()
                ? Ui.empty(Icons.FOLDER_OPEN, "No projects yet", "Open the folder of a legacy project to see what a migration would "
                        + "involve. Renova only reads it; migrations work on a copy.", openButton("Open project folder…"))
                : tiles;

        VBox page = Ui.page(
                Ui.header("Projects", "Assess and migrate legacy projects on this machine. Your code stays here; "
                        + "only AI requests leave it, on your own key.", open),
                Ui.section("Recent projects", tiles.getChildren().isEmpty() ? null : "Click a project to open its assessment.", projects));

        VBox list = new VBox();
        for (MigrationRun run : runs) {
            MigrationRun.State state = run.state().get();
            list.getChildren().add(row(run.projectName(), run.workspace().toString(), switch (state) {
                case RUNNING -> Ui.badge("Running", Ui.Tone.INFO, Icons.REFRESH);
                case PASSED -> Ui.badge("Passed", Ui.Tone.GOOD, Icons.CHECK);
                case FAILED -> Ui.badge("Failed", Ui.Tone.BAD, Icons.CROSS);
                case ERROR -> Ui.badge("Error", Ui.Tone.BAD, Icons.ALERT);
                case CANCELLED -> Ui.badge("Cancelled", Ui.Tone.MUTED, Icons.STOP);
            }, () -> nav.showRun(run)));
        }
        List<String> shown = runs.stream().map(r -> r.workspace().toAbsolutePath().normalize().toString()).toList();
        history.list().stream().filter(e -> !shown.contains(Path.of(e.workspace()).toAbsolutePath().normalize().toString()))
                .limit(Math.max(0, RECENT_MIGRATIONS - runs.size()))
                .forEach(e -> list.getChildren().add(row(e.projectName(), e.workspace(), switch (e.state()) {
                    case "PASSED" -> Ui.badge("Passed", Ui.Tone.GOOD, Icons.CHECK);
                    case "FAILED" -> Ui.badge("Failed", Ui.Tone.BAD, Icons.CROSS);
                    case "CANCELLED" -> Ui.badge("Cancelled", Ui.Tone.MUTED, Icons.STOP);
                    default -> Ui.badge("Error", Ui.Tone.BAD, Icons.ALERT);
                }, () -> nav.showWorkspace(Path.of(e.workspace())))));
        if (!list.getChildren().isEmpty()) {
            Button all = new Button("All migrations");
            all.getStyleClass().add(Styles.FLAT);
            all.setOnAction(e -> nav.migrations());
            VBox body = new VBox(list, all);
            body.setSpacing(8);
            VBox section = Ui.section("Recent migrations", "Each one is a migrated copy with its results, changes and report.", body);
            // Rows run edge to edge, like a table.
            section.getChildren().get(1).setStyle("-fx-padding: 0 0 10 0;");
            VBox.setMargin(all, new javafx.geometry.Insets(0, 0, 0, 10));
            page.getChildren().add(section);
        }
        return page;
    }

    private Button openButton(String text) {
        Button open = new Button(text, Icons.of(Icons.FOLDER_OPEN, 16));
        open.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Open a legacy project");
            File dir = chooser.showDialog(open.getScene().getWindow());
            if (dir != null) {
                nav.openProject(dir.toPath());
            }
        });
        return open;
    }

    private static Node row(String name, String where, Node badge, Runnable open) {
        VBox text = new VBox(2, Ui.label(name, Styles.TEXT_BOLD), Ui.label(where, Styles.TEXT_MUTED, Styles.TEXT_SMALL, "mono"));
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(12, text, badge);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("row-item");
        row.setOnMouseClicked(e -> open.run());
        return row;
    }
}
