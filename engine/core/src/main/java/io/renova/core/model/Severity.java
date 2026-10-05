package io.renova.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

public enum Severity {
    /** Worth knowing; the migration works without acting on it. */
    INFO,
    /** Likely to break the build or change behaviour if ignored. */
    WARNING,
    /** The target platform cannot run the system until this is resolved. */
    BLOCKER;

    @JsonCreator
    public static Severity parse(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
