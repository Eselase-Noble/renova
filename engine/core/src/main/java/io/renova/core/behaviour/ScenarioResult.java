package io.renova.core.behaviour;

import java.util.List;

/**
 * @param differences what differs between the original and the migrated answer; empty when the same
 * @param notes       what was not compared and why, e.g. a body that changes on every request
 */
public record ScenarioResult(Scenario scenario, Exchange baseline, Exchange candidate, List<String> differences,
                             List<String> notes) {

    public ScenarioResult {
        differences = List.copyOf(differences);
        notes = List.copyOf(notes);
    }

    public boolean same() {
        return differences.isEmpty();
    }
}
