package io.renova.core.behaviour;

import java.util.List;

/**
 * The comparison of one step of a scenario.
 *
 * @param step        index into {@code scenario.steps()}
 * @param differences what differs between the original and the migrated answer; empty when the same
 * @param notes       what was not compared and why, e.g. a body that changes on every request
 * @param handlerFile the project file that handles this request, where a fix belongs; null if unknown
 */
public record ScenarioResult(Scenario scenario, int step, Exchange baseline, Exchange candidate, List<String> differences,
                             List<String> notes, String handlerFile) {

    public ScenarioResult(Scenario scenario, int step, Exchange baseline, Exchange candidate, List<String> differences,
                          List<String> notes) {
        this(scenario, step, baseline, candidate, differences, notes, null);
    }

    public ScenarioResult withHandler(String file) {
        return new ScenarioResult(scenario, step, baseline, candidate, differences, notes, file);
    }

    /** The same result with these differences accepted: reported as notes, no longer differences. */
    public ScenarioResult accepting(List<String> accepted) {
        List<String> left = differences.stream().filter(d -> !accepted.contains(d)).toList();
        List<String> moreNotes = new java.util.ArrayList<>(notes);
        accepted.forEach(a -> moreNotes.add("accepted change: " + a));
        return new ScenarioResult(scenario, step, baseline, candidate, left, moreNotes, handlerFile);
    }

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
