package io.renova.core.ai;

/**
 * What a migration asked of the AI provider and what it cost, for reports and benchmarks.
 *
 * @param failed         requests that ended in a provider error
 * @param knowledgeNotes retrieved knowledge notes sent, summed over requests
 * @param referenceFiles reference-only files sent, summed over requests
 */
public record AiUsage(int requests, int changed, int unchanged, int declined, int failed,
                      long inputTokens, long outputTokens, int knowledgeNotes, int referenceFiles) {

    public static final AiUsage NONE = new AiUsage(0, 0, 0, 0, 0, 0, 0, 0, 0);
}
