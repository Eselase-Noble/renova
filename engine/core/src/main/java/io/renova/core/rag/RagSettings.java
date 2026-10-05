package io.renova.core.rag;

/**
 * Retrieval for AI requests. Off by default until benchmarks show it raises build-pass rates or
 * cuts repair rounds or tokens.
 *
 * @param budget share of the request size (see {@code AiFixer}) that retrieved context may use
 */
public record RagSettings(boolean enabled, double budget) {

    public static final double DEFAULT_BUDGET = 0.3;
    public static final RagSettings OFF = new RagSettings(false, DEFAULT_BUDGET);
    public static final RagSettings ON = new RagSettings(true, DEFAULT_BUDGET);

    public RagSettings {
        if (budget <= 0 || budget > 1) {
            throw new IllegalArgumentException("rag.budget must be greater than 0 and at most 1, not " + budget);
        }
    }
}
