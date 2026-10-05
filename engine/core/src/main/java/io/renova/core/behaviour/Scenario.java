package io.renova.core.behaviour;

import java.util.List;

/**
 * Requests sent in order to both the original and the migrated application, each run with its own
 * cookies (so sessions carry over between steps) and its own captured values.
 *
 * @param why         where the scenario comes from, e.g. "OrderController#show (Spring @RequestMapping)" or
 *                    "renova-scenarios.yaml"
 * @param handlerFile for a single request found in the code: the file a difference should be fixed in; null
 *                    to find it from the application's routes
 */
public record Scenario(String id, String why, List<Step> steps, String handlerFile) {

    public Scenario {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("Scenario " + id + " has no steps");
        }
        steps = List.copyOf(steps);
    }

    public Scenario(String id, String why, List<Step> steps) {
        this(id, why, steps, null);
    }

    /** A single request, as found from an application's entry points. */
    public Scenario(String id, String method, String path, String why) {
        this(id, method, path, why, null);
    }

    public Scenario(String id, String method, String path, String why, String handlerFile) {
        this(id, why, List.of(new Step(method, path, null, null, false, null, null)), handlerFile);
    }

    public boolean mutating() {
        return steps.stream().anyMatch(Step::mutating);
    }
}
