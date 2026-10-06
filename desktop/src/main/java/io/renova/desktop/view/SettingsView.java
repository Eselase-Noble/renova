package io.renova.desktop.view;

import atlantafx.base.controls.Message;
import atlantafx.base.controls.PasswordTextField;
import atlantafx.base.theme.Styles;
import io.renova.core.ai.NoAiProvider;
import io.renova.core.config.AiPreferences;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** The AI provider and your own API keys, saved in the Renova user config (shared with the CLI). */
public final class SettingsView {

    private static final List<String> EFFORTS = List.of("", "low", "medium", "high", "xhigh", "max");
    private final AiPreferences ai;
    private final VBox messages = new VBox(8);

    public SettingsView(AiPreferences ai) {
        this.ai = ai;
    }

    public Node build() {
        AiPreferences.View view;
        try {
            view = ai.view();
        } catch (IOException e) {
            return Ui.page(Ui.header("Settings", null), new Message("Could not read settings", e.getMessage()));
        }
        List<String> names = new ArrayList<>(List.of(NoAiProvider.NAME));
        view.providers().forEach(p -> names.add(p.name()));
        ComboBox<String> provider = new ComboBox<>();
        provider.getItems().setAll(names);
        provider.setValue(view.provider());
        provider.setMaxWidth(Double.MAX_VALUE);
        java.util.Map<String, String> labels = new java.util.HashMap<>();
        labels.put(NoAiProvider.NAME, "No AI (AI steps are listed for a person)");
        view.providers().forEach(p -> labels.put(p.name(), p.displayName()));
        provider.setConverter(converter(labels));
        TextField model = new TextField(view.model() == null ? "" : view.model());
        model.setPromptText("Provider default");
        ComboBox<String> effort = new ComboBox<>();
        effort.getItems().setAll(EFFORTS);
        effort.setValue(view.effort() == null ? "" : view.effort());
        effort.setMaxWidth(Double.MAX_VALUE);
        effort.setConverter(converter(java.util.Map.of("", "Provider default", "low", "Low", "medium", "Medium",
                "high", "High", "xhigh", "Extra high", "max", "Max")));
        CheckBox rag = new CheckBox("Retrieve context (RAG): related code, tests and migration notes in each request");
        rag.setSelected(view.rag());

        GridPane form = new GridPane(12, 10);
        form.addRow(0, Ui.label("Provider", Styles.TEXT_BOLD), provider);
        form.addRow(1, Ui.label("Model", Styles.TEXT_BOLD), model);
        form.addRow(2, Ui.label("Effort", Styles.TEXT_BOLD), effort);
        form.add(rag, 1, 3);
        GridPane.setHgrow(provider, Priority.ALWAYS);

        Button save = new Button("Save");
        save.getStyleClass().add(Styles.ACCENT);
        save.setOnAction(e -> run(() -> {
            ai.save(AiPreferences.PROVIDER, provider.getValue());
            ai.save(AiPreferences.MODEL, model.getText());
            ai.save(AiPreferences.EFFORT, effort.getValue());
            ai.save(AiPreferences.RAG, String.valueOf(rag.isSelected()));
            return "Settings saved.";
        }));
        Button check = new Button("Check key and model");
        check.setOnAction(e -> run(ai::check));
        form.add(new HBox(8, save, check), 1, 4);

        VBox keys = new VBox(12);
        for (AiPreferences.Provider p : view.providers()) {
            keys.getChildren().add(keyRow(p));
        }

        return Ui.page(
                Ui.header("Settings", "AI runs on your own provider account. Renova never supplies, pools or shares keys."),
                messages,
                Ui.section("AI provider", "Used for judgement calls, build repair and behaviour repair.", form),
                Ui.section("API keys and endpoints", "Saved in " + view.configFile() + ", readable only by you. "
                        + "Environment variables such as ANTHROPIC_API_KEY take precedence.", keys));
    }

    private static javafx.util.StringConverter<String> converter(java.util.Map<String, String> labels) {
        return new javafx.util.StringConverter<>() {
            @Override
            public String toString(String value) {
                return value == null ? "" : labels.getOrDefault(value, value);
            }

            @Override
            public String fromString(String label) {
                return labels.entrySet().stream().filter(e -> e.getValue().equals(label)).map(java.util.Map.Entry::getKey)
                        .findFirst().orElse(label);
            }
        };
    }

    private Node keyRow(AiPreferences.Provider p) {
        PasswordTextField key = new PasswordTextField();
        key.setPromptText(p.keyConfigured() ? "Replace the key" : "Paste your API key");
        HBox.setHgrow(key, Priority.ALWAYS);
        Button save = new Button("Save key");
        save.setOnAction(e -> run(() -> {
            ai.setKey(p.name(), key.getPassword());
            key.setText("");
            return p.displayName() + " key saved.";
        }));
        Button remove = new Button("Remove");
        remove.getStyleClass().add(Styles.FLAT);
        remove.setDisable(!p.keyConfigured() || !"Renova settings".equals(p.keySource()));
        remove.setOnAction(e -> run(() -> {
            ai.removeKey(p.name());
            return p.displayName() + " key removed.";
        }));
        Node status = p.keyConfigured()
                ? new HBox(8, Ui.badge("Key set", Ui.Tone.GOOD), Ui.label(p.maskedKey() + " · from " + p.keySource(), Styles.TEXT_MUTED, "mono"))
                : Ui.badge("No key", Ui.Tone.MUTED);
        TextField endpoint = new TextField(p.baseUrl() == null ? "" : p.baseUrl());
        endpoint.setPromptText("Public endpoint (set one for a gateway, proxy or on-premises server)");
        endpoint.getStyleClass().add("mono");
        HBox.setHgrow(endpoint, Priority.ALWAYS);
        Button saveEndpoint = new Button("Save endpoint");
        saveEndpoint.setOnAction(e -> run(() -> {
            ai.setBaseUrl(p.name(), endpoint.getText());
            return endpoint.getText().isBlank() ? p.displayName() + " uses its public endpoint."
                    : p.displayName() + " endpoint saved.";
        }));
        VBox row = new VBox(6, new HBox(8, Ui.label(p.displayName(), Styles.TEXT_BOLD), status), new HBox(8, key, save, remove),
                new HBox(8, endpoint, saveEndpoint));
        row.setPadding(new Insets(0, 0, 4, 0));
        return row;
    }

    private interface Action {
        String call() throws Exception;
    }

    /** Runs off the UI thread (checking a key calls the provider) and shows the result. */
    private void run(Action action) {
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return action.call();
            }
        };
        task.setOnSucceeded(e -> show(new Message("Done", task.getValue()), Styles.SUCCESS));
        task.setOnFailed(e -> show(new Message("That did not work", String.valueOf(task.getException().getMessage())), Styles.DANGER));
        Thread thread = new Thread(task, "renova-settings");
        thread.setDaemon(true);
        thread.start();
    }

    private void show(Message message, String style) {
        message.getStyleClass().add(style);
        message.setOnClose(e -> messages.getChildren().remove(message));
        messages.getChildren().setAll(message);
    }
}
