package io.renova.core.ai;

/**
 * A model that proposes file edits. Instances are created per run by an {@link AiProviderFactory}
 * with the user's own settings and credentials.
 */
public interface AiProvider extends AutoCloseable {

    String name();

    /** False only for the "none" provider; the engine then reports AI work as manual. */
    default boolean available() {
        return true;
    }

    /** The model actually used, for reports. */
    default String model() {
        return null;
    }

    /** @throws AiProviderException on provider failures */
    Proposal propose(FixRequest request);

    /** Verifies credentials and model without generating anything; returns a short description. */
    default String check() {
        return name();
    }

    @Override
    default void close() {
    }
}
