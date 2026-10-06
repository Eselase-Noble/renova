package io.renova.intellij;

import com.intellij.openapi.project.Project;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import io.renova.core.engine.PlanStep;
import io.renova.core.model.Category;
import io.renova.core.model.Finding;

import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Map;
import java.util.TreeMap;

/** Summary of the assessment, and the plan as a tree: steps, then the places they apply to. */
final class AssessmentPanel extends JPanel {

    private static final Map<String, String> STRATEGIES = Map.of(
            "recipe", "recipe", "replace", "text rule", "maven", "build file edit", "ai", "AI", "manual", "for a person");

    private final Project project;
    private final RenovaProjectService service;
    private final JBLabel summary = new JBLabel();
    private final Tree tree = new Tree(new DefaultTreeModel(new DefaultMutableTreeNode()));

    AssessmentPanel(Project project, RenovaProjectService service) {
        super(new BorderLayout());
        this.project = project;
        this.service = service;
        summary.setBorder(JBUI.Borders.empty(8));
        summary.setAllowAutoWrapping(true);
        tree.setRootVisible(false);
        tree.setCellRenderer(new Renderer());
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                    if (path != null && ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject() instanceof Finding f) {
                        RenovaUi.openFile(project, service.assessment().root().resolve(f.file()), f.line());
                    }
                }
            }
        });
        add(summary, BorderLayout.NORTH);
        add(new JBScrollPane(tree), BorderLayout.CENTER);
    }

    void refresh() {
        RenovaProjectService.Assessment a = service.assessment();
        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        if (service.assessing()) {
            summary.setText("Assessing " + project.getName() + "…");
        } else if (service.assessmentError() != null) {
            summary.setText("<html>Renova cannot assess this project: " + escape(service.assessmentError()) + "</html>");
        } else if (a == null) {
            summary.setText("<html>Press <b>Assess</b> to see what a migration must change and who resolves each step. "
                    + "Findings then appear in the editor.</html>");
        } else {
            long manual = a.plan().steps().stream().filter(s -> s.strategy().equals("manual")).count();
            Map<Category, Integer> byCategory = new TreeMap<>(java.util.Comparator.comparing(Category::code));
            a.analysis().findings().forEach(f -> byCategory.merge(f.category(), 1, Integer::sum));
            StringBuilder categories = new StringBuilder();
            byCategory.forEach((c, n) -> categories.append(c.code()).append(' ').append(n).append(" &nbsp; "));
            summary.setText("<html><b>" + a.analysis().findings().size() + " findings</b>, "
                    + Math.round(a.plan().automationRate() * 100) + "% automated, " + a.plan().steps().size() + " steps, "
                    + manual + " for a person<br>" + escape(a.playbook().name()) + "<br>"
                    + "<span style='color:gray'>By category: " + categories + "</span></html>");
            for (PlanStep step : a.plan().steps()) {
                DefaultMutableTreeNode stepNode = new DefaultMutableTreeNode(step);
                step.findings().stream().limit(200).forEach(f -> stepNode.add(new DefaultMutableTreeNode(f)));
                root.add(stepNode);
            }
        }
        tree.setModel(new DefaultTreeModel(root));
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }

    private static final class Renderer extends ColoredTreeCellRenderer {
        @Override
        public void customizeCellRenderer(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row,
                                          boolean hasFocus) {
            Object item = ((DefaultMutableTreeNode) value).getUserObject();
            if (item instanceof PlanStep s) {
                append(s.order() + ". ", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                append(s.rule().title(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                SimpleTextAttributes tag = s.strategy().equals("manual") ? SimpleTextAttributes.ERROR_ATTRIBUTES
                        : s.strategy().equals("ai") ? SimpleTextAttributes.LINK_ATTRIBUTES : SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES;
                append("   " + STRATEGIES.getOrDefault(s.strategy(), s.strategy()), tag);
                append("   " + s.rule().category().code() + " · " + s.occurrences(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                if (s.rule().fix().hint() != null) {
                    setToolTipText("<html>" + escape(s.rule().fix().hint().strip()) + "</html>");
                }
            } else if (item instanceof Finding f) {
                append(f.file() + (f.line() > 0 ? ":" + f.line() : ""), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                if (f.evidence() != null) {
                    append("   " + f.evidence(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                }
            }
        }
    }
}
