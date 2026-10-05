package io.renova.core.behaviour;

import io.renova.core.engine.MigrationContext;
import io.renova.core.engine.VerifyResult;
import io.renova.core.spi.Verifier;

import java.util.function.Function;

/**
 * Verification for AI behaviour repair: the build (with tests) first, then the side-by-side comparison.
 * Differences come back as errors attributed to the files that handle the requests, so the existing repair
 * loop can work on them like build errors, with the same guards, safety rules and audit log.
 */
public final class BehaviourCheckingVerifier implements Verifier {

    private final Verifier build;
    private final Function<MigrationContext, BehaviourReport> behaviour;
    private BehaviourReport last;

    public BehaviourCheckingVerifier(Verifier build, Function<MigrationContext, BehaviourReport> behaviour) {
        this.build = build;
        this.behaviour = behaviour;
    }

    @Override
    public VerifyResult verify(MigrationContext context) throws Exception {
        VerifyResult built = build.verify(context);
        if (!built.success()) {
            return built;
        }
        last = behaviour.apply(context);
        if (last.status() != BehaviourReport.Status.DIFFERENT) {
            return built;
        }
        return new VerifyResult(false, BehaviourErrors.of(last), "behaviour differs: " + last.summary());
    }

    /** The comparison from the last build that passed; null if none passed. */
    public BehaviourReport last() {
        return last;
    }
}
