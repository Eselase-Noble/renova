package io.renova.core.engine;

import java.util.List;

/** @param log the tail of the build output, kept for the report */
public record VerifyResult(boolean success, List<BuildError> errors, String log) {

    /** How a verifier marks a line of the log that the reader of the report has to see. */
    public static final String NOTE = "note: ";

    public VerifyResult {
        errors = List.copyOf(errors);
    }

    /**
     * What limits the result even when the build passes, in the verifier's words: for example that tests
     * which need another operating system were not run.
     */
    public List<String> notes() {
        return log == null ? List.of() : log.lines().filter(l -> l.startsWith(NOTE)).map(l -> l.substring(NOTE.length())).toList();
    }
}
