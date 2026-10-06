package io.renova.intellij;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.ui.Messages;
import io.renova.core.ai.AiSettings;
import io.renova.core.config.AiPreferences;
import io.renova.core.rag.RagSettings;
import org.jetbrains.annotations.NotNull;

/** Migrates a copy of the open project, after an assessment. */
public final class MigrateAction extends AnAction {

    public MigrateAction() {
        super("Migrate with Renova…", "Migrate a copy of this project; the project itself is never changed", AllIcons.Actions.Execute);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        var project = e.getProject();
        if (project == null) {
            return;
        }
        RenovaProjectService service = RenovaProjectService.of(project);
        RenovaProjectService.Assessment a = service.assessment();
        if (a == null) {
            Messages.showInfoMessage(project, "Assess the project first (Tools › Renova › Assess).", "Renova");
            service.assess();
            return;
        }
        AiSettings ai;
        boolean rag;
        try {
            AiPreferences prefs = new AiPreferences(service.registry());
            ai = prefs.aiSettings();
            rag = prefs.ragSettings().enabled();
        } catch (Exception ex) {
            ai = AiSettings.NONE;
            rag = RagSettings.ON.enabled();
        }
        MigrateDialog dialog = new MigrateDialog(project, a.root(), ai, rag);
        if (dialog.showAndGet()) {
            service.migrate(a, dialog.options());
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(e.getProject() != null && !RenovaProjectService.of(e.getProject()).assessing());
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
