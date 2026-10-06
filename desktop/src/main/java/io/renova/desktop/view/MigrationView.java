package io.renova.desktop.view;

import atlantafx.base.controls.Message;
import atlantafx.base.theme.Styles;
import com.fasterxml.jackson.databind.JsonNode;
import io.renova.core.workspace.WorkspaceHistory;
import io.renova.desktop.Navigator;
import io.renova.desktop.service.MigrationResult;
import io.renova.desktop.service.MigrationRun;
import io.renova.desktop.service.Phases;
import io.renova.desktop.service.DiffLines;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One migration: live progress while it runs; then, from the files in the migrated copy, its results, behaviour,
 * every stage's changes, the report, the AI exchanges and the log. Past migrations open the same way.
 */
public final class MigrationView {

    private final Navigator nav;
    private final MigrationRun run;
    private final Path workspace;
    private final VBox page = Ui.page();
    private final TabPane tabs = new TabPane();
    private String note;

    /** A migration running now. */
    public MigrationView(Navigator nav, MigrationRun run) {
        this.nav = nav;
        this.run = run;
        this.workspace = run.workspace();
    }

    /** A finished migration, from its migrated copy. */
    public MigrationView(Navigator nav, Path workspace) {
        this.nav = nav;
        this.run = null;
        this.workspace = workspace;
    }

    public Node build() {
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        render();
        if (run != null) {
            run.state().addListener((obs, old, now) -> render());
        }
        return page;
    }

    private boolean running() {
        return run != null && run.state().get() == MigrationRun.State.RUNNING;
    }

    private void render() {
        if (running() || (run != null && run.outcome() == null)) {
            renderRunning();
            return;
        }
        MigrationResult result;
        try {
            result = MigrationResult.load(workspace);
        } catch (Exception e) {
            page.getChildren().setAll(Ui.header(Icons.WORKFLOW, "Migration", workspace.toString()),
                    new Message("Cannot show this migration", e.getMessage()));
            return;
        }
        renderFinished(result);
    }

    private void renderRunning() {
        ListView<String> log = Ui.console(run.log());
        log.setPrefHeight(480);
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(18, 18);
        boolean cancelled = run.state().get() == MigrationRun.State.CANCELLED;
        boolean stopped = run.state().get() == MigrationRun.State.ERROR || cancelled;
        HBox status = new HBox(8, cancelled ? Ui.badge("Cancelled", Ui.Tone.MUTED, Icons.STOP)
                : stopped ? Ui.badge("Error", Ui.Tone.BAD, Icons.ALERT) : Ui.badge("Running", Ui.Tone.INFO, Icons.REFRESH));
        Label time = Ui.label(elapsed(), Styles.TEXT_MUTED, Styles.TEXT_SMALL);
        if (running()) {
            status.getChildren().addAll(spinner, time);
            // The clock stops with the view: the timeline ends when the run does or the page is replaced.
            Timeline clock = new Timeline(new KeyFrame(javafx.util.Duration.seconds(1), e -> time.setText(elapsed())));
            clock.setCycleCount(Timeline.INDEFINITE);
            clock.play();
            run.state().addListener((obs, old, now) -> clock.stop());
            page.sceneProperty().addListener((obs, old, now) -> {
                if (now == null) {
                    clock.stop();
                }
            });
        } else {
            status.getChildren().add(time);
        }
        status.setAlignment(Pos.CENTER_LEFT);
        Button cancel = new Button("Cancel migration", Icons.of(Icons.STOP, 14));
        cancel.setOnAction(e -> confirmCancel(cancel));
        Button folder = new Button("Open migrated folder", Icons.of(Icons.FOLDER_OPEN, 14));
        folder.setOnAction(e -> nav.openPath(workspace));
        folder.setDisable(!Files.isDirectory(workspace));
        page.getChildren().setAll(running()
                ? Ui.header(Icons.WORKFLOW, "Migration of " + run.projectName(), workspace.toString(), cancel)
                : Ui.header(Icons.WORKFLOW, "Migration of " + run.projectName(), workspace.toString(), folder), status);
        if (run.error() != null) {
            page.getChildren().add(new Message("The migration stopped", run.error()));
        }
        if (cancelled) {
            page.getChildren().add(new Message("This migration was cancelled",
                    "The stages committed before it stopped are kept in the migrated folder. The project itself was never changed."));
        }
        // The pipeline follows the log: each new line may start the next phase.
        VBox pipeline = new VBox();
        Label step = Ui.label("", Styles.TEXT_MUTED, Styles.TEXT_SMALL);
        Runnable follow = () -> {
            List<Phases.Phase> phases = Phases.of(List.copyOf(run.log()), run.options().ai() != null && !run.options().ai().equals(io.renova.core.ai.AiSettings.NONE),
                    !running(), stopped, null, null);
            pipeline.getChildren().setAll(Ui.pipeline(phases));
            step.setText(run.log().isEmpty() ? "Starting…" : run.log().getLast().replaceFirst(" to /.*$", ""));
            log.scrollTo(Math.max(0, run.log().size() - 1));
        };
        follow.run();
        run.log().addListener((ListChangeListener<String>) c -> follow.run());
        page.getChildren().add(Ui.section("Pipeline", null, new VBox(14, pipeline, step)));
        tabs.getTabs().setAll(tab("Log", log));
        page.getChildren().add(tabs);
    }

    private void renderFinished(MigrationResult r) {
        Button folder = new Button("Open migrated folder", Icons.of(Icons.FOLDER_OPEN, 14));
        folder.setOnAction(e -> nav.openPath(workspace));
        Button report = new Button("Open report", Icons.of(Icons.FILE, 14));
        report.setOnAction(e -> nav.openPath(r.reportMarkdown()));
        Button verify = new Button("Verify behaviour again", Icons.of(Icons.SHIELD, 14));
        verify.setOnAction(e -> verifyAgain(r, verify));
        Button forget = new Button("Remove from history");
        forget.getStyleClass().add(Styles.FLAT);
        forget.setOnAction(e -> nav.forget(workspace));

        String state = r.state();
        HBox status = new HBox(8, state.equals("PASSED") ? Ui.badge("Passed", Ui.Tone.GOOD, Icons.CHECK)
                : state.equals("FAILED") ? Ui.badge("Failed", Ui.Tone.BAD, Icons.CROSS) : Ui.badge("Error", Ui.Tone.BAD, Icons.ALERT));
        status.setAlignment(Pos.CENTER_LEFT);
        page.getChildren().setAll(Ui.header(Icons.WORKFLOW, "Migration of " + r.projectName(),
                        workspace + (run != null ? "  ·  " + elapsed() : ""), folder, report, verify, forget),
                status);
        page.getChildren().add(Ui.section("Pipeline", "Each stage is one commit in the migrated folder.", Ui.pipeline(phases(r))));
        if (note != null) {
            page.getChildren().add(new Message("Behaviour verified again", note));
        }
        String skipped = skippedAi(r);
        if (skipped != null) {
            Button settings = new Button("Open settings", Icons.of(Icons.KEY, 14));
            settings.setOnAction(e -> nav.settings());
            Message why = new Message("The build fails because the AI steps were not done", skipped);
            why.getStyleClass().add(Styles.WARNING);
            page.getChildren().add(new VBox(8, why, settings));
        }
        page.getChildren().add(summary(r));

        tabs.getTabs().setAll(tab("Overview", overview(r)));
        if (r.behaviour() != null) {
            tabs.getTabs().add(tab("Behaviour", behaviour(r.behaviour())));
        }
        tabs.getTabs().add(tab("Changes", changes()));
        tabs.getTabs().add(tab("Report", reportTab(r)));
        try {
            List<MigrationResult.AiExchange> ai = r.aiExchanges();
            if (!ai.isEmpty()) {
                tabs.getTabs().add(tab("AI exchanges (" + ai.size() + ")", aiTab(ai)));
            }
        } catch (Exception ignored) {
            // No AI log.
        }
        tabs.getTabs().add(tab("Log", logTab(r)));
        String wanted = nav.startTab();
        tabs.getTabs().stream().filter(t -> t.getText().toLowerCase().startsWith(String.valueOf(wanted).toLowerCase()))
                .findFirst().ifPresent(t -> tabs.getSelectionModel().select(t));
        page.getChildren().add(tabs);
    }

    private void confirmCancel(Button button) {
        Alert ask = new Alert(Alert.AlertType.CONFIRMATION, "Renova stops at the current step and ends the build it is waiting for. "
                + "Stages already committed stay in the migrated folder; the project itself is untouched.",
                new ButtonType("Cancel migration", ButtonBar.ButtonData.OK_DONE), new ButtonType("Keep running", ButtonBar.ButtonData.CANCEL_CLOSE));
        ask.setTitle("Cancel this migration?");
        ask.setHeaderText("Cancel this migration?");
        ask.initOwner(page.getScene().getWindow());
        ask.showAndWait().filter(b -> b.getButtonData() == ButtonBar.ButtonData.OK_DONE).ifPresent(b -> {
            button.setDisable(true);
            button.setText("Cancelling…");
            run.cancel();
        });
    }

    private static Tab tab(String title, Node content) {
        VBox box = new VBox(content);
        box.setPadding(new javafx.geometry.Insets(12, 0, 0, 0));
        VBox.setVgrow(content, Priority.ALWAYS);
        return new Tab(title, box);
    }

    private String elapsed() {
        Instant end = run.finished() == null ? Instant.now() : run.finished();
        long s = Duration.between(run.started(), end).toSeconds();
        return s < 60 ? s + "s" : (s / 60) + "m " + (s % 60) + "s";
    }

    private Node summary(MigrationResult r) {
        JsonNode m = r.migration();
        JsonNode v = m.path("verification");
        String build = v.isMissingNode() || v.isNull() ? "Not built" : v.path("success").asBoolean() ? "Passes" : "Fails";
        String buildHint = v.isMissingNode() || v.isNull() ? null : v.path("errors").size() + " error(s)";
        JsonNode b = r.behaviour();
        String behaviour = b == null ? "Not checked" : switch (b.path("status").asText()) {
            case "SAME" -> "Same";
            case "DIFFERENT" -> "Differs";
            case "SKIPPED" -> "Not compared";
            default -> "Comparison failed";
        };
        JsonNode ai = m.path("aiUsage");
        int requests = ai.path("requests").asInt();
        Node buildBadge = switch (build) {
            case "Passes" -> Ui.badge("Build passes", Ui.Tone.GOOD, Icons.CHECK);
            case "Fails" -> Ui.badge("Build fails", Ui.Tone.BAD, Icons.CROSS);
            default -> Ui.badge("Not built", Ui.Tone.MUTED, Icons.MINUS);
        };
        Node behaviourBadge = switch (behaviour) {
            case "Same" -> Ui.badge("Same behaviour", Ui.Tone.GOOD, Icons.CHECK);
            case "Differs" -> Ui.badge("Behaviour differs", Ui.Tone.WARN, Icons.ALERT);
            case "Comparison failed" -> Ui.badge("Comparison failed", Ui.Tone.BAD, Icons.CROSS);
            default -> Ui.badge(behaviour, Ui.Tone.MUTED, Icons.MINUS);
        };
        return new HBox(14,
                Ui.stat("Build and tests", stateRow(buildBadge), buildHint),
                Ui.stat("Behaviour", stateRow(behaviourBadge), b == null ? "Behaviour verification was off" : b.path("summary").asText()),
                Ui.stat("AI", requests > 0 ? requests + " requests" : "Not used", requests > 0
                        ? Ui.tokens(ai.path("inputTokens").asLong()) + " in · " + Ui.tokens(ai.path("outputTokens").asLong())
                        + " out · " + m.path("repairRounds").asInt() + " repair round(s)" : "AI steps are listed for a person"),
                Ui.stat("For a person", String.valueOf(m.path("manualSteps").size()), "Steps with guidance"));
    }

    /**
     * Why a failing build is expected: the plan had steps for AI, and this migration ran without it. Null when
     * that is not what happened.
     */
    private static String skippedAi(MigrationResult r) {
        JsonNode m = r.migration();
        if (r.buildPasses() || m.path("aiUsage").path("requests").asInt() > 0) {
            return null;
        }
        List<String> steps = new java.util.ArrayList<>();
        r.report().path("plan").forEach(step -> {
            if (step.path("strategy").asText().equals("ai")) {
                steps.add(step.path("title").asText());
            }
        });
        if (steps.isEmpty()) {
            return null;
        }
        return "This migration ran without AI, so " + steps.size() + (steps.size() == 1 ? " step of the plan was" : " steps of the plan were")
                + " left undone and the code that depends on " + (steps.size() == 1 ? "it" : "them") + " does not build yet:\n• "
                + String.join("\n• ", steps) + "\nAdd your own Anthropic or OpenAI key in Settings and migrate again: Renova then makes these "
                + "changes and repairs the build errors that are left. Or make the changes by hand in the migrated folder.";
    }

    /** A badge at the height of a stat's figure, so cards with a state and cards with a number line up. */
    private static Node stateRow(Node badge) {
        HBox row = new HBox(badge);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMinHeight(34);
        return row;
    }

    /** The phases of a finished migration, from its saved log and results. */
    private List<Phases.Phase> phases(MigrationResult r) {
        List<String> lines;
        try {
            lines = run != null ? List.copyOf(run.log()) : r.progressLog();
        } catch (Exception e) {
            lines = List.of();
        }
        JsonNode m = r.migration();
        if (lines.isEmpty()) {
            // No log was kept: the stages in the report say how far it got.
            List<String> fromStages = new java.util.ArrayList<>(List.of("Plan:"));
            m.path("stages").forEach(s -> fromStages.add("Stage " + s.path("stage").asText() + ":"));
            if (!m.path("verification").isMissingNode() && !m.path("verification").isNull()) {
                fromStages.add("Verifying build");
            }
            if (r.behaviour() != null) {
                fromStages.add("Verifying behaviour");
            }
            lines = fromStages;
        }
        JsonNode v = m.path("verification");
        Boolean buildOk = v.isMissingNode() || v.isNull() ? null : v.path("success").asBoolean();
        return Phases.of(lines, m.path("aiUsage").path("requests").asInt() > 0, true, r.state().equals("ERROR"), buildOk,
                r.behaviour() == null ? null : r.behaviour().path("status").asText());
    }

    private Node overview(MigrationResult r) {
        JsonNode m = r.migration();
        VBox stages = new VBox(4);
        for (JsonNode s : m.path("stages")) {
            String status = s.path("status").asText();
            Ui.Tone tone = switch (status) {
                case "APPLIED" -> Ui.Tone.GOOD;
                case "PARTIAL" -> Ui.Tone.WARN;
                case "FAILED" -> Ui.Tone.BAD;
                default -> Ui.Tone.MUTED;
            };
            Label name = Ui.label(s.path("stage").asText(), Styles.TEXT_BOLD, "mono");
            name.setMinWidth(210);
            Label summary = Ui.label(s.path("summary").asText(), Styles.TEXT_MUTED);
            summary.setPrefWidth(560);
            summary.setMinWidth(0);
            HBox head = new HBox(12, Ui.badge(switch (status) {
                case "APPLIED" -> "Applied";
                case "PARTIAL" -> "Partly applied";
                case "FAILED" -> "Failed";
                default -> "Skipped";
            }, tone, switch (status) {
                case "APPLIED" -> Icons.CHECK;
                case "PARTIAL" -> Icons.ALERT;
                case "FAILED" -> Icons.CROSS;
                default -> Icons.MINUS;
            }), name, summary);
            head.setAlignment(Pos.CENTER_LEFT);
            if (s.path("details").isEmpty()) {
                head.setPadding(new javafx.geometry.Insets(4, 0, 4, 26));
                stages.getChildren().add(head);
            } else {
                StringBuilder details = new StringBuilder();
                s.path("details").forEach(d -> details.append(d.asText()).append('\n'));
                TextArea text = new TextArea(details.toString());
                text.setEditable(false);
                text.getStyleClass().add("log");
                text.setPrefRowCount(Math.min(14, s.path("details").size() + 1));
                TitledPane pane = new TitledPane(null, text);
                pane.setGraphic(head);
                pane.setExpanded(false);
                pane.getStyleClass().add(Styles.DENSE);
                stages.getChildren().add(pane);
            }
        }
        VBox body = new VBox(16, Ui.section("Stages", "Each stage is one commit in the migrated folder. Expand a stage for its details.", stages));
        JsonNode errors = m.path("verification").path("errors");
        if (errors.size() > 0) {
            VBox list = new VBox(8);
            int shown = 0;
            for (JsonNode e : errors) {
                if (shown++ == 40) {
                    break;
                }
                String file = e.path("file").isNull() ? "(build)" : e.path("file").asText();
                int line = e.path("line").asInt();
                VBox text = new VBox(2, Ui.label(file + (line > 0 ? ":" + line : ""), "mono", Styles.TEXT_SMALL),
                        Ui.label(e.path("message").asText(), Styles.TEXT_MUTED));
                HBox.setHgrow(text, Priority.ALWAYS);
                text.setMinWidth(0);
                list.getChildren().add(new HBox(10, Icons.of(Icons.CROSS, 15, "bad"), text));
            }
            body.getChildren().add(Ui.section("Build errors", errors.size() + " from the compiler, the build file or the tests.", list));
        }
        if (m.path("manualSteps").size() > 0) {
            VBox manual = new VBox(12);
            for (JsonNode step : m.path("manualSteps")) {
                VBox item = new VBox(2, Ui.label(step.path("rule").path("title").asText(), Styles.TEXT_BOLD));
                String hint = step.path("rule").path("fix").path("hint").asText("");
                if (!hint.isBlank()) {
                    item.getChildren().add(Ui.label(hint.strip(), Styles.TEXT_MUTED));
                }
                StringBuilder files = new StringBuilder();
                int n = 0;
                for (JsonNode f : step.path("files")) {
                    if (n++ < 6) {
                        files.append(f.asText()).append("   ");
                    }
                }
                item.getChildren().add(Ui.label(files.toString().strip(), "mono", Styles.TEXT_SMALL));
                manual.getChildren().add(item);
            }
            body.getChildren().add(Ui.section("For a person", "Decisions Renova leaves to your team, with guidance.", manual));
        }
        return body;
    }

    private Node behaviour(JsonNode b) {
        VBox body = new VBox(12);
        body.getChildren().add(Ui.label(b.path("summary").asText(), Styles.TEXT_MUTED));
        if (!b.path("original").isNull() && !b.path("original").isMissingNode()) {
            body.getChildren().add(new HBox(12,
                    Ui.stat("Original", b.path("original").asText(), null),
                    Ui.stat("Migrated", b.path("migrated").asText(), null),
                    Ui.stat("Requests compared", String.valueOf(b.path("results").size()), null)));
        }
        VBox requests = new VBox(6);
        for (JsonNode r : b.path("results")) {
            boolean same = r.path("same").asBoolean();
            String label = r.path("scenario").asText().startsWith("file.")
                    ? r.path("scenario").asText().substring(5) + " · step " + r.path("step").asInt() + ": " + r.path("method").asText() + " " + r.path("path").asText()
                    : r.path("method").asText() + " " + r.path("path").asText();
            HBox head = new HBox(10, same ? Ui.badge("Same", Ui.Tone.GOOD) : Ui.badge("Different", Ui.Tone.WARN),
                    Ui.label(label, "mono"),
                    Ui.label(status(r.path("original")) + " → " + status(r.path("migrated")), Styles.TEXT_MUTED));
            head.setAlignment(Pos.CENTER_LEFT);
            VBox detail = new VBox(6);
            detail.getChildren().add(Ui.label("From " + r.path("source").asText()
                    + (r.path("handler").isTextual() ? "  ·  handled in " + r.path("handler").asText() : ""), Styles.TEXT_SMALL, Styles.TEXT_MUTED));
            r.path("differences").forEach(d -> detail.getChildren().add(Ui.label("• " + d.asText(), Styles.TEXT_SMALL)));
            r.path("notes").forEach(d -> detail.getChildren().add(Ui.label("Note: " + d.asText(), Styles.TEXT_SMALL, Styles.TEXT_MUTED)));
            HBox answers = new HBox(8, answer("Original", r.path("original")), answer("Migrated", r.path("migrated")));
            detail.getChildren().add(answers);
            TitledPane pane = new TitledPane(null, detail);
            pane.setGraphic(head);
            pane.setExpanded(!same);
            pane.getStyleClass().add(Styles.DENSE);
            requests.getChildren().add(pane);
        }
        body.getChildren().add(Ui.section("Requests", "Expand a request to see both answers.", requests));
        if (b.path("databases").size() > 0) {
            VBox db = new VBox(6);
            b.path("databases").fields().forEachRemaining(e -> {
                List<String> diffs = new java.util.ArrayList<>();
                e.getValue().forEach(d -> diffs.add(d.asText()));
                db.getChildren().add(new HBox(10, diffs.isEmpty() ? Ui.badge("Same rows", Ui.Tone.GOOD) : Ui.badge("Different rows", Ui.Tone.WARN),
                        Ui.label(e.getKey().replaceFirst("^file\\.", "") + (diffs.isEmpty() ? "" : ": " + String.join("; ", diffs)), Styles.TEXT_SMALL)));
            });
            body.getChildren().add(Ui.section("Database changes", "Rows each version added and removed during the same scenario.", db));
        }
        if (b.path("accepted").size() > 0) {
            VBox accepted = new VBox(4);
            b.path("accepted").forEach(a -> accepted.getChildren().add(Ui.label(a.asText(), Styles.TEXT_SMALL)));
            body.getChildren().add(Ui.section("Accepted changes", "Listed under accept: in the scenario file as intended.", accepted));
        }
        return body;
    }

    private static Node answer(String side, JsonNode e) {
        String meta = side + "  " + status(e) + (e.path("contentType").isTextual() ? "  " + e.path("contentType").asText() : "");
        TextArea body = new TextArea(e.path("error").isTextual() ? e.path("error").asText() : e.path("body").asText(""));
        body.setEditable(false);
        body.setWrapText(true);
        body.getStyleClass().add("log");
        body.setPrefRowCount(8);
        VBox box = new VBox(4, Ui.label(meta, Styles.TEXT_SMALL, Styles.TEXT_BOLD), body);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    private static String status(JsonNode exchange) {
        int code = exchange.path("status").asInt(-1);
        return code <= 0 ? "no answer" : String.valueOf(code);
    }

    private Node reportTab(MigrationResult r) {
        try {
            return MarkdownView.of(r.markdown());
        } catch (Exception e) {
            return new Message("No report", String.valueOf(e.getMessage()));
        }
    }

    private Node aiTab(List<MigrationResult.AiExchange> exchanges) {
        ListView<MigrationResult.AiExchange> list = new ListView<>(FXCollections.observableArrayList(exchanges));
        list.setCellFactory(l -> new ListCell<>() {
            @Override
            protected void updateItem(MigrationResult.AiExchange x, boolean empty) {
                super.updateItem(x, empty);
                if (empty || x == null) {
                    setText(null);
                    return;
                }
                String outcome = x.markdown().lines().filter(line -> line.startsWith("- **Outcome:**")).findFirst()
                        .map(line -> line.replace("- **Outcome:** ", "")).orElse("");
                String target = x.markdown().lines().filter(line -> line.contains("(target)")).findFirst()
                        .map(line -> line.replaceAll(".*`([^`]+)`.*", "$1")).orElse("");
                setText("Exchange " + Integer.parseInt(x.name()) + "  ·  " + outcome + "\n" + target);
            }
        });
        VBox right = new VBox();
        list.getSelectionModel().selectedItemProperty().addListener((obs, old, x) -> {
            if (x != null) {
                var view = MarkdownView.of(x.markdown());
                VBox.setVgrow(view, Priority.ALWAYS);
                right.getChildren().setAll(view);
            }
        });
        list.getSelectionModel().selectFirst();
        SplitPane split = new SplitPane(list, right);
        split.setDividerPositions(0.34);
        split.setPrefHeight(640);
        return new VBox(8, Ui.label("Every request Renova sent to the AI provider: the files offered and their roles, the rules or "
                + "errors, what the model answered and the tokens used.", Styles.TEXT_MUTED, Styles.TEXT_SMALL), split);
    }

    private Node logTab(MigrationResult r) {
        List<String> lines;
        try {
            lines = run != null ? List.copyOf(run.log()) : r.progressLog();
        } catch (Exception e) {
            lines = List.of();
        }
        ListView<String> log = Ui.console(FXCollections.observableArrayList(lines));
        log.setPrefHeight(560);
        log.setPlaceholder(new Label("No log was kept for this migration."));
        return log;
    }

    private void verifyAgain(MigrationResult r, Button button) {
        if (!Files.isDirectory(workspace.resolve(".git"))) {
            note = "The migrated folder is not a Renova workspace.";
            render();
            return;
        }
        button.setDisable(true);
        button.setText("Verifying…");
        String playbook = r.report().path("playbook").path("id").asText();
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return nav.engine().verifyBehaviour(workspace, playbook, Map.of(), this::updateMessage).summary();
            }
        };
        task.messageProperty().addListener((obs, old, message) -> button.setText(message.length() > 48 ? message.substring(0, 48) + "…" : message));
        task.setOnSucceeded(e -> {
            note = task.getValue();
            render();
        });
        task.setOnFailed(e -> {
            note = "Could not verify: " + task.getException().getMessage();
            render();
        });
        Thread thread = new Thread(task, "renova-behaviour");
        thread.setDaemon(true);
        thread.start();
    }

    /** The stages as commits on the left, the selected stage's diff on the right. */
    private Node changes() {
        ListView<WorkspaceHistory.Commit> commits = new ListView<>();
        commits.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(WorkspaceHistory.Commit c, boolean empty) {
                super.updateItem(c, empty);
                setText(null);
                if (empty || c == null) {
                    setGraphic(null);
                    return;
                }
                Label title = Ui.label(c.message().replaceFirst("^renova: ", ""), Styles.TEXT_BOLD);
                Label stats = Ui.label(c.hash().substring(0, Math.min(7, c.hash().length())) + "  ·  " + c.filesChanged() + " file(s)  ·  +"
                        + c.insertions() + " −" + c.deletions(), Styles.TEXT_MUTED, Styles.TEXT_SMALL, "mono");
                VBox box = new VBox(2, title, stats);
                box.getStyleClass().add("commit-cell");
                // Wrap the title to the list's width rather than widening the list.
                box.maxWidthProperty().bind(list.widthProperty().subtract(24));
                setGraphic(box);
            }
        });
        ListView<DiffLines.Line> diff = new ListView<>();
        diff.getStyleClass().addAll("diff", Styles.DENSE);
        diff.setPlaceholder(new Label("No file changes in this stage."));
        diff.setCellFactory(list -> new ListCell<>() {
            private final Label before = new Label();
            private final Label after = new Label();
            private final Label sign = new Label();
            private final Label code = new Label();
            private final HBox numbered = new HBox(before, after, sign, code);
            /** File names, hunk headers and notes span the row; they have no line numbers. */
            private final Label heading = new Label();

            {
                before.getStyleClass().add("gutter");
                after.getStyleClass().add("gutter");
                sign.getStyleClass().add("sign");
                code.getStyleClass().add("code");
                heading.getStyleClass().add("code");
            }

            @Override
            protected void updateItem(DiffLines.Line line, boolean empty) {
                super.updateItem(line, empty);
                getStyleClass().removeAll("added", "removed", "hunk", "file", "note");
                setText(null);
                if (empty || line == null) {
                    setGraphic(null);
                    return;
                }
                switch (line.kind()) {
                    case FILE, HUNK, NOTE -> {
                        getStyleClass().add(line.kind().name().toLowerCase());
                        heading.setText(line.text());
                        setGraphic(heading);
                    }
                    default -> {
                        if (line.kind() != DiffLines.Kind.CONTEXT) {
                            getStyleClass().add(line.kind().name().toLowerCase());
                        }
                        before.setText(line.before() > 0 ? String.valueOf(line.before()) : "");
                        after.setText(line.after() > 0 ? String.valueOf(line.after()) : "");
                        sign.setText(line.kind() == DiffLines.Kind.ADDED ? "+" : line.kind() == DiffLines.Kind.REMOVED ? "−" : "");
                        code.setText(line.text());
                        setGraphic(numbered);
                    }
                }
            }
        });
        commits.getSelectionModel().selectedItemProperty().addListener((obs, old, c) -> {
            if (c != null) {
                background(() -> DiffLines.parse(WorkspaceHistory.diff(workspace, c.hash()), 20_000), lines -> diff.getItems().setAll(lines));
            }
        });
        background(() -> WorkspaceHistory.commits(workspace), list -> {
            commits.getItems().setAll(list.size() > 1 ? list.subList(1, list.size()) : List.of());
            if (!commits.getItems().isEmpty()) {
                commits.getSelectionModel().selectFirst();
            }
        });
        SplitPane split = new SplitPane(commits, diff);
        split.setDividerPositions(0.28);
        split.setPrefHeight(600);
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
