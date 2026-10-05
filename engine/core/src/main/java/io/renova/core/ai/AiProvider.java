package io.renova.core.ai;

import java.util.Optional;

/**
 * A model that proposes file edits. Kept vendor-neutral so customers can choose a hosted model or
 * one running inside their own network. Implementations are discovered with ServiceLoader.
 */
public interface AiProvider {

    /** Selected with {@code --ai <name>}. */
    String name();

    /** False when, for example, credentials are missing; the engine then reports work as manual. */
    boolean available();

    /** Returns the complete new content of the file, or empty when the model declines. */
    Optional<FilePatch> propose(FixRequest request) throws Exception;
}
