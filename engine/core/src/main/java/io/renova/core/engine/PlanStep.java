package io.renova.core.engine;

import io.renova.core.playbook.Rule;

import java.util.List;

/**
 * One rule's worth of work.
 *
 * @param files project-relative files with at least one finding, in scan order
 */
public record PlanStep(int order, Rule rule, int occurrences, List<String> files) {

    public PlanStep {
        files = List.copyOf(files);
    }

    public String strategy() {
        return rule.fix().strategy();
    }
}
