package io.renova.core.spi;

import io.renova.core.engine.MigrationContext;
import io.renova.core.engine.VerifyResult;

/** Proves the migrated workspace still builds (and, later, still behaves the same). */
public interface Verifier {
    VerifyResult verify(MigrationContext context) throws Exception;
}
