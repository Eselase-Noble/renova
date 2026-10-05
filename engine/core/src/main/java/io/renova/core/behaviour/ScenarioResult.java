package io.renova.core.behaviour;

import java.util.List;

/**
 * The comparison of one step of a scenario.
 *
 * @param step        index into {@code scenario.steps()}
 * @param differences what differs between the original and the migrated answer; empty when the same
 * @param notes       what was not compared and why, e.g. a body that changes on every request
 */
public record ScenarioResult(Scenario scenario, int step, Exchange baseline, Exchange candidate, List<String> differences,
                             List<String> notes) {

    public ScenarioResult {
        differences = List.copyOf(differences);
        notes = List.copyOf(notes);
    }

    public boolean same() {
        return differences.isEmpty();
    }

    public String method() {
        return scenario.steps().get(step).method();
    }

    public String path() {
        return scenario.steps().get(step).path();
    }

    /** "GET /items", or "checkout step 2: POST /cart" for multi-step scenarios. */
    public String label() {
        return scenario.steps().size() == 1 ? method() + " " + path()
                : scenario.id() + " step " + (step + 1) + ": " + method() + " " + path();
    }
}
