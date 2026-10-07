package io.renova.desktop.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Where a migration is, read from its progress lines: each phase starts at a line the engine always writes
 * ("Stage recipe: …", "Verifying build", …). Phases the migration never reached are left out when it is over,
 * except the ones every migration has.
 */
public final class Phases {

    public enum State { DONE, CURRENT, PENDING, SKIPPED, FAILED, WARN }

    /** @param note the last progress line that belongs to the phase; null if it has not started */
    public record Phase(String key, String label, State state, String note) {
    }

    private record Definition(String key, String label, Pattern starts, boolean always) {
    }

    private static final List<Definition> DEFINITIONS = List.of(
            new Definition("plan", "Plan", Pattern.compile("^(Plan:|Copying project)"), true),
            new Definition("rewrite", "Rewrite", Pattern.compile("^Stage (recipe|replace|maven|gradle|dotnet)"), true),
            new Definition("ai", "AI edits", Pattern.compile("^Stage ai"), false),
            new Definition("guards", "Guards", Pattern.compile("^(Checking \\d+ guard|Stage guard)"), true),
            new Definition("build", "Build and tests", Pattern.compile("^Verifying build"), true),
            new Definition("repair", "AI repair", Pattern.compile("^Build fails with"), false),
            new Definition("behaviour", "Behaviour", Pattern.compile("^(Verifying behaviour|Behaviour differs)"), false));

    private static final Pattern END = Pattern.compile("^(Finished|Error|Cancelled)");

    private Phases() {
    }

    /**
     * @param lines     the progress lines so far
     * @param ai        whether AI is used; without it the engine still announces the AI stage, which then only lists
     *                  those steps for a person
     * @param over      whether the migration has ended
     * @param stopped   whether it ended with an error rather than a result
     * @param buildOk   whether the build passed; null if it was not verified (or not yet)
     * @param behaviour the behaviour status (SAME, DIFFERENT, SKIPPED, FAILED); null if not compared
     */
    public static List<Phase> of(List<String> lines, boolean ai, boolean over, boolean stopped, Boolean buildOk, String behaviour) {
        Map<String, String> notes = new LinkedHashMap<>();
        int reached = -1;
        for (String line : lines) {
            int found = -1;
            for (int i = 0; i < DEFINITIONS.size(); i++) {
                if (DEFINITIONS.get(i).starts().matcher(line).find() && (ai || !DEFINITIONS.get(i).key().equals("ai"))) {
                    found = i;
                    break;
                }
            }
            if (found >= 0) {
                reached = Math.max(reached, found);
                notes.put(DEFINITIONS.get(found).key(), line);
            } else if (reached >= 0 && !END.matcher(line).find()) {
                notes.put(DEFINITIONS.get(reached).key(), line);
            }
        }
        List<Phase> phases = new ArrayList<>();
        for (int i = 0; i < DEFINITIONS.size(); i++) {
            Definition d = DEFINITIONS.get(i);
            boolean started = notes.containsKey(d.key());
            if (!started && !d.always()) {
                continue;
            }
            State state = !started ? (over ? State.SKIPPED : State.PENDING) : i == reached && !over ? State.CURRENT : State.DONE;
            if (over && i == reached && stopped) {
                state = State.FAILED;
            }
            if (over && d.key().equals("build") && Boolean.FALSE.equals(buildOk)) {
                state = State.FAILED;
            }
            if (over && d.key().equals("repair") && Boolean.FALSE.equals(buildOk)) {
                state = State.WARN;
            }
            if (over && d.key().equals("behaviour") && "DIFFERENT".equals(behaviour)) {
                state = State.WARN;
            }
            if (over && d.key().equals("behaviour") && "FAILED".equals(behaviour)) {
                state = State.FAILED;
            }
            phases.add(new Phase(d.key(), d.label(), state, notes.get(d.key())));
        }
        return phases;
    }
}
