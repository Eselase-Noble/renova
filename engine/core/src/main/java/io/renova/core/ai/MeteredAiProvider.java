package io.renova.core.ai;

/** Counts the requests, outcomes and tokens that pass through a provider. */
public final class MeteredAiProvider implements AiProvider {

    private final AiProvider delegate;
    private int requests;
    private int changed;
    private int unchanged;
    private int declined;
    private int failed;
    private long inputTokens;
    private long outputTokens;
    private int knowledgeNotes;
    private int referenceFiles;

    public MeteredAiProvider(AiProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public boolean available() {
        return delegate.available();
    }

    @Override
    public String model() {
        return delegate.model();
    }

    @Override
    public synchronized Proposal propose(FixRequest request) {
        requests++;
        knowledgeNotes += request.knowledge().size();
        referenceFiles += (int) request.files().stream().filter(f -> f.role() == RequestFile.Role.REFERENCE).count();
        Proposal proposal;
        try {
            proposal = delegate.propose(request);
        } catch (RuntimeException e) {
            failed++;
            throw e;
        }
        inputTokens += proposal.inputTokens();
        outputTokens += proposal.outputTokens();
        switch (proposal.outcome()) {
            case CHANGED -> changed++;
            case UNCHANGED -> unchanged++;
            case DECLINED -> declined++;
        }
        return proposal;
    }

    @Override
    public String check() {
        return delegate.check();
    }

    @Override
    public void close() {
        delegate.close();
    }

    public synchronized AiUsage usage() {
        return new AiUsage(requests, changed, unchanged, declined, failed, inputTokens, outputTokens,
                knowledgeNotes, referenceFiles);
    }
}
