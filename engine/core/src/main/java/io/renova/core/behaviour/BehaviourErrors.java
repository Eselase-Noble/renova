package io.renova.core.behaviour;

import io.renova.core.engine.BuildError;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns behaviour differences into errors the AI repair loop works on, like build errors: each is
 * attributed to the file that handles the request (a controller, a JSP, or the routing configuration)
 * and explains what the original answered and what the migrated application answers instead.
 */
public final class BehaviourErrors {

    private static final int SNIPPET = 300;

    private BehaviourErrors() {
    }

    public static List<BuildError> of(BehaviourReport report) {
        String platforms = report.baselinePlatform() == null ? "" : " (original on " + report.baselinePlatform()
                + ", migrated on " + report.candidatePlatform() + ")";
        List<BuildError> errors = new ArrayList<>();
        if (report.status() == BehaviourReport.Status.DIFFERENT && report.results().isEmpty()) {
            errors.add(new BuildError(null, 0, "behaviour differs" + platforms + ": " + report.summary()));
        }
        for (ScenarioResult r : report.results()) {
            if (r.same()) {
                continue;
            }
            StringBuilder message = new StringBuilder("behaviour differs from the original application").append(platforms)
                    .append(" for ").append(r.label()).append(" (from ").append(r.scenario().why()).append("): ")
                    .append(String.join("; ", r.differences()));
            if (r.baseline().responded() && r.candidate().responded() && r.baseline().status() < 400) {
                message.append(". The original answered: ").append(snippet(r.baseline().text()));
            }
            message.append(". Make the migrated application answer as the original did, without changing the request.");
            errors.add(new BuildError(r.handlerFile(), 0, message.toString()));
        }
        for (Map.Entry<String, List<String>> db : report.databases().entrySet()) {
            if (db.getValue().isEmpty()) {
                continue;
            }
            String handler = report.results().stream()
                    .filter(r -> r.scenario().id().equals(db.getKey()) && r.scenario().steps().get(r.step()).mutating())
                    .map(ScenarioResult::handlerFile).filter(java.util.Objects::nonNull).findFirst().orElse(null);
            errors.add(new BuildError(handler, 0, "behaviour differs from the original application" + platforms
                    + ": scenario " + db.getKey() + " changed the database differently: " + String.join("; ", db.getValue())
                    + ". Make the migrated application store exactly what the original stored."));
        }
        return errors;
    }

    private static String snippet(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return "\"" + (flat.length() > SNIPPET ? flat.substring(0, SNIPPET) + "…" : flat) + "\"";
    }
}
