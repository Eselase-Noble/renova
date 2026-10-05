package io.renova.core.behaviour;

import java.util.List;
import java.util.Map;

/**
 * The outcome of running the original and the migrated application side by side.
 *
 * @param baselinePlatform  e.g. "Java 8, Tomcat 9"; null when nothing ran
 * @param candidatePlatform e.g. "Java 21, Tomcat 10.1"
 * @param databases         by scenario id: how the two applications' database changes differed (empty when the same)
 * @param candidateLog      the tail of the migrated application's log, for startup failures
 */
public record BehaviourReport(Status status, String summary, String baselinePlatform, String candidatePlatform,
                              List<ScenarioResult> results, Map<String, List<String>> databases,
                              String baselineLog, String candidateLog) {

    public enum Status {
        /** Every scenario answered the same. */
        SAME,
        /** At least one scenario answered differently, or the migrated application did not start. */
        DIFFERENT,
        /** Not run: unsupported project, no scenarios, or no container runtime. */
        SKIPPED,
        /** Could not be completed, e.g. the original application did not build or start. */
        FAILED
    }

    public BehaviourReport {
        results = results == null ? List.of() : List.copyOf(results);
        databases = databases == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(databases));
    }

    public static BehaviourReport skipped(String why) {
        return new BehaviourReport(Status.SKIPPED, why, null, null, List.of(), Map.of(), null, null);
    }

    public static BehaviourReport failed(String why, String baselineLog, String candidateLog) {
        return new BehaviourReport(Status.FAILED, why, null, null, List.of(), Map.of(), baselineLog, candidateLog);
    }

    /** Steps answered differently plus scenarios whose database changes differed. */
    public long differing() {
        return results.stream().filter(r -> !r.same()).count() + databases.values().stream().filter(d -> !d.isEmpty()).count();
    }
}
