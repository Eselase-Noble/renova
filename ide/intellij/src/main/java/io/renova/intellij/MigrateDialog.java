package io.renova.intellij;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import io.renova.core.ai.AiSettings;
import io.renova.core.engine.MigrationOptions;
import io.renova.core.engine.Migrator;
import io.renova.core.rag.RagSettings;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Migration options, as in the console and the desktop app. */
public final class MigrateDialog extends DialogWrapper {

    private final AiSettings ai;
    private final boolean aiReady;
    private final JBCheckBox useAi = new JBCheckBox("Use AI for judgement calls and repair");
    private final JBCheckBox rag = new JBCheckBox("Retrieve context (related code, tests, migration notes)");
    private final JBCheckBox tests = new JBCheckBox("Run the project's tests", true);
    private final JBCheckBox behaviour = new JBCheckBox("Verify behaviour: run the original and the migrated app side by side (Docker)");
    private final ComboBox<Integer> rounds = new ComboBox<>(new Integer[] {0, 1, 2, 3, 5});
    private final TextFieldWithBrowseButton out = new TextFieldWithBrowseButton();

    public MigrateDialog(Project project, Path root, AiSettings ai, boolean ragDefault) {
        super(project);
        this.ai = ai;
        this.aiReady = !ai.equals(AiSettings.NONE) && ai.apiKey() != null;
        useAi.setSelected(aiReady);
        useAi.setEnabled(aiReady);
        rag.setSelected(aiReady && ragDefault);
        rag.setEnabled(aiReady);
        rounds.setSelectedItem(3);
        rounds.setEnabled(aiReady);
        useAi.addActionListener(e -> {
            rag.setEnabled(useAi.isSelected());
            rounds.setEnabled(useAi.isSelected());
        });
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        out.setText(root.resolveSibling(root.getFileName() + "-renova-" + stamp).toString());
        out.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Folder for the Migrated Copy"));
        setTitle("Migrate " + root.getFileName() + " with Renova");
        setOKButtonText("Start Migration");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JBLabel aiNote = new JBLabel(aiReady ? "AI: " + ai.provider() + " (" + ai.model() + ") on your own key."
                : "No AI provider with a key is set up (Settings › Tools › Renova). AI steps are then listed for a person.");
        aiNote.setForeground(UIUtil.getContextHelpForeground());
        return FormBuilder.createFormBuilder()
                .addComponent(useAi)
                .addComponentToRightColumn(aiNote)
                .addComponent(rag)
                .addLabeledComponent("AI repair rounds:", rounds)
                .addComponent(tests)
                .addComponent(behaviour)
                .addLabeledComponent("Migrated copy:", out)
                .addComponentToRightColumn(new JBLabel("Renova never changes this project; each stage is a commit in the copy."))
                .getPanel();
    }

    public MigrationOptions options() {
        Map<String, String> tools = new LinkedHashMap<>();
        if (!tests.isSelected()) {
            tools.put("verify.skipTests", "true");
        }
        if (behaviour.isSelected()) {
            tools.put(Migrator.VERIFY_BEHAVIOUR, "true");
        }
        boolean withAi = useAi.isSelected() && aiReady;
        return new MigrationOptions(Path.of(out.getText().strip()), withAi ? ai : AiSettings.NONE,
                withAi ? (Integer) rounds.getSelectedItem() : 0, true, tools, List.of(),
                withAi && rag.isSelected() ? RagSettings.ON : RagSettings.OFF);
    }
}
