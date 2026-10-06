package io.renova.intellij;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/** The Renova tool window: the project's assessment, and the migrations started from it. */
public final class RenovaToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        RenovaProjectService service = RenovaProjectService.of(project);
        DefaultActionGroup actions = new DefaultActionGroup(new AssessAction(), new MigrateAction());

        AssessmentPanel assessment = new AssessmentPanel(project, service);
        SimpleToolWindowPanel assessmentTab = new SimpleToolWindowPanel(true, true);
        var toolbar = ActionManager.getInstance().createActionToolbar("RenovaToolWindow", actions, true);
        toolbar.setTargetComponent(assessment);
        assessmentTab.setToolbar(toolbar.getComponent());
        assessmentTab.setContent(assessment);

        MigrationsPanel migrations = new MigrationsPanel(project, service);

        ContentFactory factory = ContentFactory.getInstance();
        Content first = factory.createContent(assessmentTab, "Assessment", false);
        Content second = factory.createContent(migrations, "Migrations", false);
        toolWindow.getContentManager().addContent(first);
        toolWindow.getContentManager().addContent(second);

        Runnable refresh = () -> {
            assessment.refresh();
            migrations.refresh();
        };
        service.addListener(refresh);
        first.setDisposer(() -> service.removeListener(refresh));
        refresh.run();
    }
}
