package io.renova.core.ai;

import java.util.Optional;

/** Used when no provider is configured; every AI step is reported as manual work. */
public final class NoAiProvider implements AiProvider {

    public static final String NAME = "none";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public Optional<FilePatch> propose(FixRequest request) {
        return Optional.empty();
    }
}
