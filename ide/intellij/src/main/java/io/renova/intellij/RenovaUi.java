package io.renova.intellij;

import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

import java.nio.file.Path;

/** Opening files, reports and migrated copies from the plugin. */
public final class RenovaUi {

    private RenovaUi() {
    }

    /** Opens a project file at a 1-based line (0 for the top). */
    public static void openFile(Project project, Path file, int line) {
        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file);
        if (vf == null) {
            Messages.showWarningDialog(project, "Cannot find " + file, "Renova");
            return;
        }
        new OpenFileDescriptor(project, vf, Math.max(0, line - 1), 0).navigate(true);
    }

    public static void openReport(Project project, RenovaProjectService.Run run) {
        VirtualFile report = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(run.workspace().resolve(".renova/report.md"));
        if (report != null) {
            FileEditorManager.getInstance(project).openFile(report, true);
        }
    }

    /** Opens the migrated copy as a project; it is a git repository with one commit per stage. */
    public static void openWorkspace(Project project, RenovaProjectService.Run run) {
        ProjectUtil.openOrImport(run.workspace(), project, true);
    }
}
