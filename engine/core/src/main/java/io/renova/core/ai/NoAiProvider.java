package io.renova.core.ai;

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
    public Proposal propose(FixRequest request) {
        return Proposal.declined("no AI provider configured", 0, 0);
    }
}
