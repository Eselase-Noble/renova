package io.renova.core.spi;

import io.renova.core.engine.MigrationContext;
import io.renova.core.engine.VerifyResult;

/** Proves the migrated workspace still builds (and, later, still behaves the same). */
public interface Verifier {

    /**
     * Checks, before anything is changed, that this machine can verify the migration at all: for example that
     * the JDK the target needs is installed. Throws {@link IllegalStateException} with what to do about it.
     */
    default void preflight(MigrationContext context) {
    }

    VerifyResult verify(MigrationContext context) throws Exception;
}
