package io.renova.core.engine;

import io.renova.core.model.Category;
import io.renova.core.model.Finding;
import io.renova.core.playbook.Rule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Turns findings into ordered steps: build changes first, behaviour-sensitive changes last, and
 * playbook order within a category.
 */
public final class Planner {

    public MigrationPlan plan(AnalysisResult analysis) {
        Map<String, List<Finding>> byRule = analysis.findings().stream()
                .collect(Collectors.groupingBy(Finding::ruleId));
        List<Rule> rules = analysis.playbook().rules();

        List<Rule> matched = rules.stream()
                .filter(r -> byRule.containsKey(r.id()))
                .sorted(Comparator.comparing(Rule::category, Category.BY_EXECUTION_ORDER)
                        .thenComparingInt(rules::indexOf))
                .toList();

        List<PlanStep> steps = new ArrayList<>();
        for (Rule rule : matched) {
            List<Finding> findings = byRule.get(rule.id());
            List<String> files = new ArrayList<>(findings.stream()
                    .map(Finding::file)
                    .collect(Collectors.toCollection(LinkedHashSet::new)));
            steps.add(new PlanStep(steps.size() + 1, rule, findings.size(), files, findings));
        }
        return new MigrationPlan(analysis.playbook(), steps);
    }
}
