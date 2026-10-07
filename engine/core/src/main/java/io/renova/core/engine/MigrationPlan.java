package io.renova.core.engine;

import io.renova.core.playbook.FixSpec;
import io.renova.core.playbook.Playbook;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record MigrationPlan(Playbook playbook, List<PlanStep> steps) {

    /** Strategies whose changes need no human or AI decision. */
    /** Strategies that need a model or a person; every other one (recipes, text rules, an ecosystem's own edits) is deterministic. */
    public static final Set<String> NOT_DETERMINISTIC = Set.of(FixSpec.AI, FixSpec.MANUAL);

    public MigrationPlan {
        steps = List.copyOf(steps);
    }

    public List<PlanStep> steps(String strategy) {
        return steps.stream().filter(s -> s.strategy().equals(strategy)).toList();
    }

    /** Findings per strategy, the basis of the automation score. */
    public Map<String, Integer> occurrencesByStrategy() {
        Map<String, Integer> result = new LinkedHashMap<>();
        steps.forEach(s -> result.merge(s.strategy(), s.occurrences(), Integer::sum));
        return result;
    }

    /** Share of findings (0..1) that deterministic fixers resolve without review. */
    public double automationRate() {
        int total = steps.stream().mapToInt(PlanStep::occurrences).sum();
        if (total == 0) {
            return 1.0;
        }
        int automated = steps.stream().filter(s -> !NOT_DETERMINISTIC.contains(s.strategy()))
                .mapToInt(PlanStep::occurrences).sum();
        return (double) automated / total;
    }
}
