package io.renova.intellij;

import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import io.renova.core.model.Finding;
import io.renova.core.model.Severity;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Shows the latest assessment's findings in the editor and the Problems view, at their lines, with what will
 * be done about them. It reads the cached assessment and never analyses on its own: run Assess to refresh it.
 */
public final class RenovaInspection extends LocalInspectionTool {

    @Override
    public ProblemDescriptor @NotNull [] checkFile(@NotNull PsiFile file, @NotNull InspectionManager manager, boolean isOnTheFly) {
        RenovaProjectService service = RenovaProjectService.of(file.getProject());
        RenovaProjectService.Assessment a = service.assessment();
        VirtualFile vf = file.getVirtualFile();
        if (a == null || vf == null || vf.getFileSystem() != com.intellij.openapi.vfs.LocalFileSystem.getInstance()) {
            return ProblemDescriptor.EMPTY_ARRAY;
        }
        Path path = vf.toNioPath();
        if (!path.startsWith(a.root())) {
            return ProblemDescriptor.EMPTY_ARRAY;
        }
        List<Finding> findings = a.findingsByFile().get(a.root().relativize(path).toString().replace('\\', '/'));
        Document document = PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
        if (findings == null || document == null || document.getLineCount() == 0) {
            return ProblemDescriptor.EMPTY_ARRAY;
        }
        List<ProblemDescriptor> problems = new ArrayList<>();
        for (Finding f : findings) {
            int line = f.line() <= 0 ? 0 : Math.min(f.line() - 1, document.getLineCount() - 1);
            TextRange range = new TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line));
            if (range.isEmpty()) {
                continue;
            }
            problems.add(manager.createProblemDescriptor(file, range, message(a, f), highlight(f), isOnTheFly));
        }
        return problems.toArray(ProblemDescriptor.EMPTY_ARRAY);
    }

    private static String message(RenovaProjectService.Assessment a, Finding f) {
        String strategy = a.plan().steps().stream().filter(s -> s.rule().id().equals(f.ruleId())).findFirst()
                .map(s -> switch (s.strategy()) {
                    case "recipe", "replace", "maven", "gradle", "dotnet", "dotnet-source" -> "Renova fixes this automatically";
                    case "ai" -> "Renova fixes this with AI, checked by the build";
                    case "manual" -> "For a person: see the guidance in the Renova tool window";
                    default -> "Strategy " + s.strategy();
                }).orElse("");
        return "Renova (" + f.category().code() + "): " + f.title() + (strategy.isEmpty() ? "" : ". " + strategy + ".");
    }

    private static ProblemHighlightType highlight(Finding f) {
        return f.severity() == Severity.BLOCKER ? ProblemHighlightType.WARNING : ProblemHighlightType.WEAK_WARNING;
    }
}
