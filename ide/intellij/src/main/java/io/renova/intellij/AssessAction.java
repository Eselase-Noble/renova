package io.renova.intellij;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

/** Analyses the open project and shows the assessment. */
public final class AssessAction extends AnAction {

    public AssessAction() {
        super("Assess with Renova", "Find what a migration must change and who resolves it", AllIcons.Actions.Refresh);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        if (e.getProject() == null) {
            return;
        }
        var window = ToolWindowManager.getInstance(e.getProject()).getToolWindow("Renova");
        if (window != null) {
            window.show();
        }
        RenovaProjectService.of(e.getProject()).assess();
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
