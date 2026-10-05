package io.renova.core.engine;

import java.util.List;

/** @param log the tail of the build output, kept for the report */
public record VerifyResult(boolean success, List<BuildError> errors, String log) {

    public VerifyResult {
        errors = List.copyOf(errors);
    }
}
