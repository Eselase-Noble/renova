package io.renova.core.ai;

/**
 * A provider's answer for one file.
 *
 * @param newContent the complete new file content; null unless {@link Outcome#CHANGED}
 * @param rationale  why the file was changed, left alone, or declined
 */
public record Proposal(Outcome outcome, String newContent, String rationale, long inputTokens, long outputTokens) {

    public enum Outcome { CHANGED, UNCHANGED, DECLINED }

    public static Proposal changed(String content, String rationale, long in, long out) {
        return new Proposal(Outcome.CHANGED, content, rationale, in, out);
    }

    public static Proposal unchanged(String rationale, long in, long out) {
        return new Proposal(Outcome.UNCHANGED, null, rationale, in, out);
    }

    public static Proposal declined(String reason, long in, long out) {
        return new Proposal(Outcome.DECLINED, null, reason, in, out);
    }
}
