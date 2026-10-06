package io.renova.intellij;

import com.intellij.openapi.project.Project;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import io.renova.core.engine.StageResult;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;

/** Migrations started in this session: state, stages and log, and links to the report and the migrated copy. */
final class MigrationsPanel extends JPanel {

    private final Project project;
    private final RenovaProjectService service;
    private final DefaultListModel<RenovaProjectService.Run> model = new DefaultListModel<>();
    private final JBList<RenovaProjectService.Run> list = new JBList<>(model);
    private final JBTextArea details = new JBTextArea();
    private final JButton report = new JButton("Open Report");
    private final JButton workspace = new JButton("Open Migrated Copy");

    MigrationsPanel(Project project, RenovaProjectService service) {
        super(new BorderLayout());
        this.project = project;
        this.service = service;
        list.setEmptyText("No migrations yet: assess the project, then Migrate.");
        list.setCellRenderer(new ColoredListCellRenderer<>() {
            @Override
            protected void customizeCellRenderer(JList<? extends RenovaProjectService.Run> l, RenovaProjectService.Run run,
                                                 int index, boolean selected, boolean focus) {
                append(run.state.name().toLowerCase(), switch (run.state) {
                    case PASSED -> SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES;
                    case RUNNING -> SimpleTextAttributes.LINK_ATTRIBUTES;
                    default -> SimpleTextAttributes.ERROR_ATTRIBUTES;
                });
                append("   " + run.workspace().getFileName(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
            }
        });
        list.addListSelectionListener(e -> showSelected());
        details.setEditable(false);
        details.setFont(JBUI.Fonts.create(java.awt.Font.MONOSPACED, 12));
        report.addActionListener(e -> {
            if (list.getSelectedValue() != null) {
                RenovaUi.openReport(project, list.getSelectedValue());
            }
        });
        workspace.addActionListener(e -> {
            if (list.getSelectedValue() != null) {
                RenovaUi.openWorkspace(project, list.getSelectedValue());
            }
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(report);
        buttons.add(workspace);
        JPanel right = new JPanel(new BorderLayout());
        right.add(buttons, BorderLayout.NORTH);
        right.add(new JBScrollPane(details), BorderLayout.CENTER);
        JBSplitter split = new JBSplitter(false, 0.3f);
        split.setFirstComponent(new JBScrollPane(list));
        split.setSecondComponent(right);
        add(split, BorderLayout.CENTER);
    }

    void refresh() {
        RenovaProjectService.Run selected = list.getSelectedValue();
        model.clear();
        service.runs().forEach(model::addElement);
        list.setSelectedValue(selected != null ? selected : model.isEmpty() ? null : model.get(0), true);
        showSelected();
    }

    private void showSelected() {
        RenovaProjectService.Run run = list.getSelectedValue();
        boolean done = run != null && run.outcome != null;
        report.setEnabled(done);
        workspace.setEnabled(done);
        if (run == null) {
            details.setText("");
            return;
        }
        StringBuilder text = new StringBuilder("Migrated copy: ").append(run.workspace()).append("\n\n");
        if (run.outcome != null) {
            var v = run.outcome.verification();
            text.append("Build and tests: ").append(v == null ? "not built" : v.success() ? "pass" : "fail (" + v.errors().size() + " errors)").append('\n');
            if (run.outcome.behaviour() != null) {
                text.append("Behaviour: ").append(run.outcome.behaviour().summary()).append('\n');
            }
            text.append("AI: ").append(run.outcome.aiUsage().requests()).append(" requests, ")
                    .append(run.outcome.aiUsage().inputTokens()).append(" in / ").append(run.outcome.aiUsage().outputTokens())
                    .append(" out tokens\n\nStages:\n");
            for (StageResult s : run.outcome.stages()) {
                text.append(String.format("  %-8s %-26s %s%n", s.status().name().toLowerCase(), s.stage(), s.summary()));
            }
            run.outcome.manualSteps().forEach(s -> text.append("\nFor a person: ").append(s.rule().title()));
            text.append("\n\nThe migrated copy is a git repository with one commit per stage: open it to review each stage "
                    + "in the Git log.\n\nLog:\n");
        }
        run.log.forEach(line -> text.append(line).append('\n'));
        details.setText(text.toString());
    }
}
