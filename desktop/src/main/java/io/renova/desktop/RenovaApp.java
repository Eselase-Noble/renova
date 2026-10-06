package io.renova.desktop;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import atlantafx.base.theme.Styles;
import io.renova.core.config.AiPreferences;
import io.renova.desktop.service.Engine;
import io.renova.desktop.service.MigrationHistory;
import io.renova.desktop.service.MigrationResult;
import io.renova.desktop.service.MigrationRun;
import io.renova.desktop.service.RecentProjects;
import io.renova.desktop.view.HomeView;
import io.renova.desktop.view.MarkdownView;
import io.renova.desktop.view.MigrationsView;
import io.renova.desktop.view.MigrationView;
import io.renova.desktop.view.ProjectView;
import io.renova.desktop.view.SettingsView;
import io.renova.desktop.view.Ui;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * The Renova desktop app: assess and migrate legacy projects on this machine. Everything runs in this process;
 * only AI requests leave the machine, on the user's own key.
 *
 * <p>Options: {@code --open=DIR} opens a project at start; {@code --show=settings} opens Settings;
 * {@code --show=migrations} opens the migration history; {@code --workspace=DIR} shows a migrated copy;
 * {@code --theme=light|dark} chooses the theme (and remembers it). Development aids: {@code --snapshot-dir=DIR} saves
 * a PNG of each screen shortly after it is shown
 * (at least {@code --snapshot-delay=SECONDS} after); {@code --migrate} (with {@code --open}) starts a migration with the
 * default options and no AI once the project is assessed; {@code --tab=NAME} opens that tab of a finished migration.
 */
public final class RenovaApp extends Application implements Navigator {

    private final Preferences prefs = Preferences.userNodeForPackage(RenovaApp.class);
    private final ObservableList<MigrationRun> runs = FXCollections.observableArrayList();
    private Engine engine;
    private AiPreferences ai;
    private RecentProjects recent;
    private MigrationHistory history;
    private BorderPane root;
    private VBox runList;
    private Button homeButton;
    private Button settingsButton;
    private Button migrationsButton;
    private Path snapshotDir;
    private Scene scene;

    @Override
    public void start(Stage stage) {
        engine = new Engine();
        ai = new AiPreferences(engine.registry());
        recent = new RecentProjects();
        history = new MigrationHistory();
        String theme = getParameters().getNamed().get("theme");
        if (theme != null) {
            prefs.putBoolean("dark", theme.equalsIgnoreCase("dark"));
        }
        applyTheme(prefs.getBoolean("dark", false));

        root = new BorderPane();
        root.setLeft(sidebar());
        scene = new Scene(root, 1280, 820);
        scene.getStylesheets().add(RenovaApp.class.getResource("app.css").toExternalForm());
        stage.setTitle("Renova");
        stage.setScene(scene);
        stage.setMinWidth(960);
        stage.setMinHeight(640);
        stage.show();

        String snapshot = getParameters().getNamed().get("snapshot-dir");
        snapshotDir = snapshot == null ? null : Path.of(snapshot);
        String open = getParameters().getNamed().get("open");
        if (open != null) {
            openProject(Path.of(open));
        } else if (getParameters().getNamed().get("workspace") != null) {
            showWorkspace(Path.of(getParameters().getNamed().get("workspace")));
        } else if ("settings".equals(getParameters().getNamed().get("show"))) {
            settings();
        } else if ("migrations".equals(getParameters().getNamed().get("show"))) {
            migrations();
        } else {
            home();
        }
    }

    private Node sidebar() {
        Label mark = new Label("R");
        mark.getStyleClass().add("brand-mark");
        HBox brand = new HBox(8, mark, Ui.label("Renova", Styles.TITLE_4));
        brand.setAlignment(Pos.CENTER_LEFT);

        homeButton = navButton("Projects", e -> home());
        migrationsButton = navButton("Migrations", e -> migrations());
        settingsButton = navButton("Settings", e -> settings());
        runList = new VBox(2);
        runs.addListener((ListChangeListener<MigrationRun>) c -> refreshRuns());

        Button theme = new Button("Light / dark");
        theme.getStyleClass().addAll(Styles.FLAT, Styles.SMALL);
        theme.setOnAction(e -> {
            boolean dark = !prefs.getBoolean("dark", false);
            prefs.putBoolean("dark", dark);
            applyTheme(dark);
        });

        Label caption = new Label("MIGRATIONS THIS SESSION");
        caption.getStyleClass().add("nav-caption");
        javafx.scene.layout.Region gap = new javafx.scene.layout.Region();
        gap.setMinHeight(16);
        VBox sidebar = new VBox(brand, gap, homeButton, migrationsButton, settingsButton, caption, runList, Ui.grow(), theme);
        sidebar.getStyleClass().add("sidebar");
        return sidebar;
    }

    private Button navButton(String text, javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        Button b = new Button(text);
        b.getStyleClass().add("nav-button");
        b.setWrapText(true);
        b.setOnAction(action);
        return b;
    }

    private void refreshRuns() {
        runList.getChildren().clear();
        for (MigrationRun run : runs) {
            Button b = navButton(run.projectName() + "\n" + stateText(run.state().get()), e -> showRun(run));
            run.state().addListener((obs, old, now) -> b.setText(run.projectName() + "\n" + stateText(now)));
            runList.getChildren().add(b);
        }
    }

    private static String stateText(MigrationRun.State state) {
        return switch (state) {
            case RUNNING -> "running";
            case PASSED -> "passed";
            case FAILED -> "failed";
            case ERROR -> "error";
        };
    }

    private void applyTheme(boolean dark) {
        MarkdownView.dark = dark;
        Application.setUserAgentStylesheet(dark ? new PrimerDark().getUserAgentStylesheet() : new PrimerLight().getUserAgentStylesheet());
    }

    private void show(Node content, Button active, String snapshotName) {
        for (Button b : new Button[] {homeButton, migrationsButton, settingsButton}) {
            b.getStyleClass().remove("active");
        }
        if (active != null) {
            active.getStyleClass().add("active");
        }
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add(Styles.BG_DEFAULT);
        root.setCenter(scroll);
        snapshot(snapshotName, 1.5);
    }

    @Override
    public void home() {
        show(new HomeView(this, recent, runs).build(), homeButton, "home");
    }

    @Override
    public void settings() {
        show(new SettingsView(ai).build(), settingsButton, "settings");
    }

    @Override
    public void openProject(Path path) {
        recent.opened(path);
        show(new ProjectView(this, engine, ai, path).build(), homeButton, "project");
    }

    @Override
    public void startRun(MigrationRun run, Runnable work) {
        runs.addFirst(run);
        run.state().addListener((obs, old, now) -> {
            if (now != MigrationRun.State.RUNNING) {
                record(run);
            }
        });
        Thread thread = new Thread(work, "renova-migration");
        thread.setDaemon(true);
        thread.start();
        showRun(run);
    }

    @Override
    public void showRun(MigrationRun run) {
        show(new MigrationView(this, run).build(), null, "migration");
        run.state().addListener((obs, old, now) -> snapshot("migration-finished", 2.5));
    }

    /** Keeps the run's log with its reports and adds it to the history. */
    private void record(MigrationRun run) {
        Path ws = run.workspace();
        MigrationResult.saveProgress(ws, List.copyOf(run.log()));
        String started = run.started().toString();
        String finished = run.finished() == null ? null : run.finished().toString();
        try {
            MigrationResult r = MigrationResult.load(ws);
            var m = r.migration();
            var v = m.path("verification");
            String build = v.isMissingNode() || v.isNull() ? "Not built" : v.path("success").asBoolean() ? "Passes" : "Fails";
            String behaviour = r.behaviour() == null ? "Not checked" : switch (r.behaviour().path("status").asText()) {
                case "SAME" -> "Same";
                case "DIFFERENT" -> "Differs";
                case "SKIPPED" -> "Not compared";
                default -> "Comparison failed";
            };
            var ai = m.path("aiUsage");
            history.add(new MigrationHistory.Entry(run.projectName(), r.report().path("project").path("root").asText(null), ws.toString(),
                    r.state(), started, finished, build, behaviour, ai.path("requests").asInt(),
                    ai.path("inputTokens").asLong() + ai.path("outputTokens").asLong()));
        } catch (Exception e) {
            if (java.nio.file.Files.isDirectory(ws)) {
                history.add(new MigrationHistory.Entry(run.projectName(), null, ws.toString(), "ERROR", started, finished,
                        "Not built", "Not checked", 0, 0));
            }
        }
    }

    @Override
    public void migrations() {
        show(new MigrationsView(this, history).build(), migrationsButton, "migrations");
    }

    @Override
    public void showWorkspace(Path workspace) {
        Path ws = workspace.toAbsolutePath().normalize();
        runs.stream().filter(r -> r.workspace().toAbsolutePath().normalize().equals(ws)).findFirst()
                .ifPresentOrElse(this::showRun, () -> show(new MigrationView(this, ws).build(), migrationsButton, "migration"));
    }

    @Override
    public void forget(Path workspace) {
        history.remove(workspace.toString());
        runs.removeIf(r -> r.workspace().equals(workspace) && r.state().get() != MigrationRun.State.RUNNING);
        migrations();
    }

    @Override
    public Engine engine() {
        return engine;
    }

    @Override
    public void openPath(Path path) {
        getHostServices().showDocument(path.toUri().toString());
    }

    /** Saves a PNG of the window after a delay, when --snapshot-dir is set. */
    @Override
    public void snapshot(String name, double delaySeconds) {
        if (snapshotDir == null) {
            return;
        }
        String minimum = getParameters().getNamed().get("snapshot-delay");
        double delay = minimum == null ? delaySeconds : Math.max(delaySeconds, Double.parseDouble(minimum));
        PauseTransition wait = new PauseTransition(Duration.seconds(delay));
        wait.setOnFinished(e -> {
            try {
                WritableImage image = scene.snapshot(null);
                int w = (int) image.getWidth();
                int h = (int) image.getHeight();
                BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                PixelReader pixels = image.getPixelReader();
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        out.setRGB(x, y, pixels.getArgb(x, y));
                    }
                }
                Files.createDirectories(snapshotDir);
                ImageIO.write(out, "png", snapshotDir.resolve(name + ".png").toFile());
            } catch (Exception ex) {
                System.err.println("Snapshot failed: " + ex);
            }
        });
        wait.play();
    }

    @Override
    public String startTab() {
        return getParameters().getNamed().get("tab");
    }

    @Override
    public boolean autoMigrate() {
        return getParameters().getUnnamed().contains("--migrate");
    }

    @Override
    public void stop() {
        Platform.exit();
    }
}
