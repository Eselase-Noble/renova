package io.renova.core.engine;

import io.renova.core.model.Finding;
import io.renova.core.playbook.Rule;

import java.util.List;

/**
 * One rule's worth of work.
 *
 * @param files    project-relative files with at least one finding, in scan order
 * @param findings the findings themselves, for fixers that need their details
 */
public record PlanStep(int order, Rule rule, int occurrences, List<String> files, List<Finding> findings) {

    public PlanStep {
        files = List.copyOf(files);
        findings = List.copyOf(findings);
    }

    public String strategy() {
        return rule.fix().strategy();
    }
}
