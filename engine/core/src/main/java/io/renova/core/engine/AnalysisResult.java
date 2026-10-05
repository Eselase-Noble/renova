package io.renova.core.engine;

import io.renova.core.model.Category;
import io.renova.core.model.Finding;
import io.renova.core.model.ProjectModel;
import io.renova.core.playbook.Playbook;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * @param warnings problems with the analysis itself (unknown detector, unreadable file), never
 *                 problems in the project
 */
public record AnalysisResult(ProjectModel project, Playbook playbook, List<Finding> findings, List<String> warnings) {

    public AnalysisResult {
        findings = List.copyOf(findings);
        warnings = List.copyOf(warnings);
    }

    public Map<Category, Long> countByCategory() {
        Map<Category, Long> counts = new EnumMap<>(Category.class);
        findings.forEach(f -> counts.merge(f.category(), 1L, Long::sum));
        return counts;
    }
}
