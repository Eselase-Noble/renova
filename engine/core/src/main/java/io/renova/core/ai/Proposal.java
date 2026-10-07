package io.renova.core.ai;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A provider's answer to one request.
 *
 * @param edits     new complete content by project-relative path; empty unless {@link Outcome#CHANGED}
 * @param rationale   why files were changed, left alone, or the request declined
 * @param rawResponse the model's answer as received, for the audit log; null if none
 * @param deletes     project-relative paths to remove; only honoured where the request allows it
 */
public record Proposal(Outcome outcome, Map<String, String> edits, String rationale, long inputTokens, long outputTokens,
                       String rawResponse, List<String> deletes) {

    public enum Outcome { CHANGED, UNCHANGED, DECLINED }

    public Proposal {
        edits = edits == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(edits));
        deletes = deletes == null ? List.of() : List.copyOf(deletes);
    }

    public Proposal(Outcome outcome, Map<String, String> edits, String rationale, long inputTokens, long outputTokens,
                    String rawResponse) {
        this(outcome, edits, rationale, inputTokens, outputTokens, rawResponse, List.of());
    }

    public Proposal withDeletes(List<String> paths) {
        return new Proposal(outcome, edits, rationale, inputTokens, outputTokens, rawResponse, paths);
    }

    public static Proposal changed(Map<String, String> edits, String rationale, long in, long out) {
        return new Proposal(Outcome.CHANGED, edits, rationale, in, out, null);
    }

    public static Proposal unchanged(String rationale, long in, long out) {
        return new Proposal(Outcome.UNCHANGED, null, rationale, in, out, null);
    }

    public static Proposal declined(String reason, long in, long out) {
        return new Proposal(Outcome.DECLINED, null, reason, in, out, null);
    }

    public Proposal withRawResponse(String raw) {
        return new Proposal(outcome, edits, rationale, inputTokens, outputTokens, raw, deletes);
    }
}
