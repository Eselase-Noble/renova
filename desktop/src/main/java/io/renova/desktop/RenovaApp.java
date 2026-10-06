package io.renova.desktop;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import io.renova.core.config.AiPreferences;
import io.renova.desktop.service.Engine;
import io.renova.desktop.service.MigrationHistory;
import io.renova.desktop.service.MigrationResult;
import io.renova.desktop.service.MigrationRun;
import io.renova.desktop.service.RecentProjects;
import io.renova.desktop.view.HomeView;
import io.renova.desktop.view.Icons;
import io.renova.desktop.view.MarkdownView;
import io.renova.desktop.view.MigrationsView;
import io.renova.desktop.view.MigrationView;
import io.renova.desktop.view.OverviewView;
import io.renova.desktop.view.PortfolioView;
import io.renova.desktop.view.ProjectView;
import io.renova.desktop.view.SettingsView;
import io.renova.desktop.view.Ui;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
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
 * {@code --show=migrations} opens the migration history; {@code --portfolio=DIR} assesses every project under a folder; {@code --workspace=DIR} shows a migrated copy;
 * {@code --theme=light|dark} chooses the theme (and remembers it). Development aids: {@code --snapshot-dir=DIR} saves
 * a PNG of each screen shortly after it is shown
 * (at least {@code --snapshot-delay=SECONDS} after); {@code --migrate} (with {@code --open}) starts a migration with the
 * default options and no AI once the project is assessed; {@code --cancel-after=SECONDS} cancels a migration that long
 * after it starts; {@code --tab=NAME} opens that tab of a finished migration.
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
    private Button overviewButton;
    private Button homeButton;
    private Button portfolioButton;
    /** The portfolio assessed in this session, so coming back to the screen does not assess it again. */
    private io.renova.core.engine.Portfolio.Result portfolio;
    private Button settingsButton;
    private Button migrationsButton;
    private Button themeButton;
    private Label runCaption;
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
        root = new BorderPane();
        root.setLeft(sidebar());
        scene = new Scene(root, 1320, 860);
        scene.getStylesheets().add(RenovaApp.class.getResource("app.css").toExternalForm());
        applyTheme(prefs.getBoolean("dark", false));
        stage.setTitle("Renova");
        stage.setScene(scene);
        stage.setMinWidth(1040);
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
        } else if (getParameters().getNamed().get("portfolio") != null) {
            show(new PortfolioView(this, null, result -> portfolio = result).build(Path.of(getParameters().getNamed().get("portfolio"))),
                    portfolioButton, "portfolio-assessing");
        } else if ("portfolio".equals(getParameters().getNamed().get("show"))) {
            portfolio();
        } else if ("projects".equals(getParameters().getNamed().get("show"))) {
            home();
        } else {
            overview();
        }
    }

    private Node sidebar() {
        // The mark: two chevrons rising out of a base line, as in the web console.
        SVGPath upper = new SVGPath();
        upper.setContent("M9 17.5 16 11l7 6.5");
        upper.getStyleClass().add("chevron");
        SVGPath lower = new SVGPath();
        lower.setContent("M9 23.5 16 17l7 6.5");
        lower.getStyleClass().addAll("chevron", "faint");
        StackPane mark = new StackPane(new Group(upper, lower));
        mark.getStyleClass().add("brand-mark");
        HBox brand = new HBox(10, mark, Ui.label("Renova", "brand-name"));
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.setPadding(new Insets(2, 6, 14, 6));

        overviewButton = navButton("Overview", Icons.DASHBOARD, e -> overview());
        homeButton = navButton("Projects", Icons.FOLDER, e -> home());
        portfolioButton = navButton("Portfolio", Icons.CHART, e -> portfolio());
        migrationsButton = navButton("Migrations", Icons.WORKFLOW, e -> migrations());
        settingsButton = navButton("Settings", Icons.SETTINGS, e -> settings());
        runList = new VBox(2);
        runCaption = new Label("THIS SESSION");
        runCaption.getStyleClass().add("nav-caption");
        runCaption.setVisible(false);
        runCaption.setManaged(false);
        runs.addListener((ListChangeListener<MigrationRun>) c -> refreshRuns());

        themeButton = navButton("", Icons.MOON, e -> {
            boolean dark = !prefs.getBoolean("dark", false);
            prefs.putBoolean("dark", dark);
            applyTheme(dark);
        });
        Label note = new Label("Your code stays on this machine.");
        note.getStyleClass().add("sidebar-note");
        note.setWrapText(true);
        VBox foot = new VBox(4, note, themeButton);
        foot.getStyleClass().add("sidebar-foot");

        Label caption = new Label("WORKSPACE");
        caption.getStyleClass().add("nav-caption");
        caption.setPadding(new Insets(0, 10, 6, 10));
        VBox sidebar = new VBox(brand, caption, overviewButton, homeButton, portfolioButton, migrationsButton, settingsButton, runCaption, runList, Ui.grow(), foot);
        sidebar.getStyleClass().add("sidebar");
        return sidebar;
    }

    private Button navButton(String text, String icon, javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        Button b = new Button(text, Icons.of(icon, 16));
        b.getStyleClass().add("nav-button");
        b.setOnAction(action);
        return b;
    }

    private void refreshRuns() {
        runCaption.setVisible(!runs.isEmpty());
        runCaption.setManaged(!runs.isEmpty());
        runList.getChildren().clear();
        for (MigrationRun run : runs) {
            Button b = new Button();
            b.getStyleClass().add("run-button");
            b.setOnAction(e -> showRun(run));
            Runnable update = () -> {
                MigrationRun.State state = run.state().get();
                b.setText(run.projectName() + "\n" + stateText(state));
                b.setGraphic(Ui.dot(switch (state) {
                    case RUNNING -> Ui.Tone.INFO;
                    case PASSED -> Ui.Tone.GOOD;
                    case FAILED, ERROR -> Ui.Tone.BAD;
                    case CANCELLED -> Ui.Tone.MUTED;
                }));
            };
            update.run();
            run.state().addListener((obs, old, now) -> update.run());
            runList.getChildren().add(b);
        }
    }

    private static String stateText(MigrationRun.State state) {
        return switch (state) {
            case RUNNING -> "Running";
            case PASSED -> "Passed";
            case FAILED -> "Failed";
            case ERROR -> "Stopped with an error";
            case CANCELLED -> "Cancelled";
        };
    }

    private void applyTheme(boolean dark) {
        MarkdownView.dark = dark;
        Application.setUserAgentStylesheet(dark ? new PrimerDark().getUserAgentStylesheet() : new PrimerLight().getUserAgentStylesheet());
        // The class the stylesheet keys Renova's colours on.
        root.getStyleClass().removeAll("renova-light", "renova-dark");
        root.getStyleClass().add(dark ? "renova-dark" : "renova-light");
        themeButton.setText(dark ? "Light theme" : "Dark theme");
        themeButton.setGraphic(Icons.of(dark ? Icons.SUN : Icons.MOON, 16));
    }

    private void show(Node content, Button active, String snapshotName) {
        for (Button b : new Button[] {overviewButton, homeButton, portfolioButton, migrationsButton, settingsButton}) {
            b.getStyleClass().remove("active");
        }
        if (active != null) {
            active.getStyleClass().add("active");
        }
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("canvas");
        root.setCenter(scroll);
        snapshot(snapshotName, 1.5);
    }

    @Override
    public void overview() {
        show(new OverviewView(this, ai, recent, history, runs).build(), overviewButton, "overview");
    }

    @Override
    public void portfolio() {
        show(new PortfolioView(this, portfolio, result -> portfolio = result).build(), portfolioButton, "portfolio");
    }

    @Override
    public void home() {
        show(new HomeView(this, recent, history, runs).build(), homeButton, "home");
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
        run.worker(thread);
        String cancelAfter = getParameters().getNamed().get("cancel-after");
        if (cancelAfter != null) {
            PauseTransition wait = new PauseTransition(Duration.seconds(Double.parseDouble(cancelAfter)));
            wait.setOnFinished(e -> run.cancel());
            wait.play();
        }
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
                    ai.path("inputTokens").asLong() + ai.path("outputTokens").asLong(),
                    r.report().path("summary").path("automationRate").isNumber() ? r.report().path("summary").path("automationRate").asDouble() : null));
        } catch (Exception e) {
            if (java.nio.file.Files.isDirectory(ws)) {
                history.add(new MigrationHistory.Entry(run.projectName(), null, ws.toString(),
                        run.state().get() == MigrationRun.State.CANCELLED ? "CANCELLED" : "ERROR", started, finished,
                        "Not built", "Not checked", 0, 0, null));
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
