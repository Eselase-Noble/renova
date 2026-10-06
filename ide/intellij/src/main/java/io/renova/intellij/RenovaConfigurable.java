package io.renova.intellij;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import io.renova.core.ai.NoAiProvider;
import io.renova.core.config.AiPreferences;
import io.renova.core.engine.PluginRegistry;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings › Tools › Renova: the AI provider and your own keys, kept in the Renova user config that the CLI and
 * the desktop app share. Keys are only ever shown masked.
 */
public final class RenovaConfigurable implements Configurable {

    private static PluginRegistry registry;
    private AiPreferences prefs;
    private ComboBox<String> provider;
    private final JBTextField model = new JBTextField();
    private final ComboBox<String> effort = new ComboBox<>(new String[] {"", "low", "medium", "high", "xhigh", "max"});
    private final JBCheckBox rag = new JBCheckBox("Retrieve context (RAG): related code, tests and migration notes in each request");
    private final Map<String, JBPasswordField> keys = new LinkedHashMap<>();
    private AiPreferences.View loaded;

    @Override
    public @Nls String getDisplayName() {
        return "Renova";
    }

    @Override
    public @Nullable JComponent createComponent() {
        synchronized (RenovaConfigurable.class) {
            if (registry == null) {
                registry = PluginRegistry.load();
            }
        }
        prefs = new AiPreferences(registry);
        List<String> names = new ArrayList<>(List.of(NoAiProvider.NAME));
        registry.aiProviders().forEach(f -> names.add(f.name()));
        provider = new ComboBox<>(names.toArray(String[]::new));
        model.getEmptyText().setText("Provider default");
        FormBuilder form = FormBuilder.createFormBuilder()
                .addLabeledComponent("AI provider:", provider)
                .addLabeledComponent("Model:", model)
                .addLabeledComponent("Effort:", effort)
                .addComponent(rag)
                .addSeparator();
        for (var f : registry.aiProviders()) {
            JBPasswordField field = new JBPasswordField();
            keys.put(f.name(), field);
            form.addLabeledComponent(f.displayName() + " key:", field);
        }
        JBLabel note = new JBLabel("Leave a key empty to keep it. Keys are your own, saved in the Renova user config "
                + "(~/.config/renova/config.properties) shared with the CLI and desktop app; environment variables take precedence.");
        note.setForeground(UIUtil.getContextHelpForeground());
        note.setAllowAutoWrapping(true);
        return form.addComponent(note).addComponentFillVertically(new javax.swing.JPanel(), 0).getPanel();
    }

    @Override
    public void reset() {
        try {
            loaded = prefs.view();
        } catch (Exception e) {
            return;
        }
        provider.setSelectedItem(loaded.provider());
        model.setText(loaded.model() == null ? "" : loaded.model());
        effort.setSelectedItem(loaded.effort() == null ? "" : loaded.effort());
        rag.setSelected(loaded.rag());
        for (AiPreferences.Provider p : loaded.providers()) {
            JBPasswordField field = keys.get(p.name());
            field.setText("");
            field.getEmptyText().setText(p.keyConfigured() ? "Key set: " + p.maskedKey() + " (" + p.keySource() + ")" : "No key");
        }
    }

    @Override
    public boolean isModified() {
        if (loaded == null) {
            return false;
        }
        boolean keyEntered = keys.values().stream().anyMatch(f -> f.getPassword().length > 0);
        return keyEntered || !String.valueOf(provider.getSelectedItem()).equals(loaded.provider())
                || !model.getText().strip().equals(loaded.model() == null ? "" : loaded.model())
                || !String.valueOf(effort.getSelectedItem()).equals(loaded.effort() == null ? "" : loaded.effort())
                || rag.isSelected() != loaded.rag();
    }

    @Override
    public void apply() throws ConfigurationException {
        try {
            prefs.save(AiPreferences.PROVIDER, String.valueOf(provider.getSelectedItem()));
            prefs.save(AiPreferences.MODEL, model.getText());
            prefs.save(AiPreferences.EFFORT, String.valueOf(effort.getSelectedItem()));
            prefs.save(AiPreferences.RAG, String.valueOf(rag.isSelected()));
            for (Map.Entry<String, JBPasswordField> k : keys.entrySet()) {
                char[] key = k.getValue().getPassword();
                if (key.length > 0) {
                    prefs.setKey(k.getKey(), new String(key));
                }
            }
        } catch (Exception e) {
            throw new ConfigurationException(e.getMessage(), "Renova");
        }
        reset();
    }
}
