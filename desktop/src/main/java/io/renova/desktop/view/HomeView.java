package io.renova.desktop.view;

import atlantafx.base.theme.Styles;
import io.renova.desktop.Navigator;
import io.renova.desktop.service.MigrationRun;
import io.renova.desktop.service.RecentProjects;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.nio.file.Path;

/** Open a project folder, or pick one opened before. */
public final class HomeView {

    private final Navigator nav;
    private final RecentProjects recent;
    private final ObservableList<MigrationRun> runs;

    public HomeView(Navigator nav, RecentProjects recent, ObservableList<MigrationRun> runs) {
        this.nav = nav;
        this.recent = recent;
        this.runs = runs;
    }

    public Node build() {
        Button open = new Button("Open project folder…");
        open.getStyleClass().add(Styles.ACCENT);
        open.setDefaultButton(true);
        open.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Open a legacy project");
            File dir = chooser.showDialog(open.getScene().getWindow());
            if (dir != null) {
                nav.openProject(dir.toPath());
            }
        });

        FlowPane tiles = new FlowPane(12, 12);
        for (RecentProjects.Entry entry : recent.list()) {
            VBox tile = new VBox(4, Ui.label(entry.name(), Styles.TEXT_BOLD), Ui.label(entry.path(), Styles.TEXT_MUTED, Styles.TEXT_SMALL, "mono"));
            tile.getStyleClass().add("recent-tile");
            tile.setPrefWidth(300);
            tile.setOnMouseClicked(e -> nav.openProject(Path.of(entry.path())));
            tiles.getChildren().add(tile);
        }
        Node projects = tiles.getChildren().isEmpty()
                ? Ui.label("No projects yet. Open the folder of a legacy project to see its assessment.", Styles.TEXT_MUTED)
                : tiles;

        VBox page = Ui.page(
                Ui.header("Projects", "Assess and migrate legacy projects on this machine. Your code stays here; "
                        + "only AI requests leave it, on your own key.", open),
                Ui.section("Recent projects", null, projects));
        if (!runs.isEmpty()) {
            VBox list = new VBox(6);
            for (MigrationRun run : runs) {
                Button b = new Button(run.projectName() + " — " + run.state().get().name().toLowerCase() + " — " + run.workspace());
                b.getStyleClass().addAll(Styles.FLAT);
                b.setOnAction(e -> nav.showRun(run));
                list.getChildren().add(b);
            }
            page.getChildren().add(Ui.section("Migrations this session", null, list));
        }
        return page;
    }
}
