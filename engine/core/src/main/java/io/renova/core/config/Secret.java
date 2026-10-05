package io.renova.core.config;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Objects;

/**
 * A credential such as an API key. {@link #toString()} and JSON serialisation never show the value,
 * so a secret cannot leak into logs, reports or exception messages by accident.
 */
public final class Secret {

    private final String value;

    private Secret(String value) {
        this.value = value;
    }

    /** Returns null for null or blank input, so "not configured" has one representation. */
    public static Secret of(String value) {
        return value == null || value.isBlank() ? null : new Secret(value.strip());
    }

    /** The real value. Call only where it is handed to the service that needs it. */
    public String reveal() {
        return value;
    }

    /** A hint safe to show the user, e.g. "sk-ant-…9f3a". */
    public String masked() {
        if (value.length() <= 12) {
            return "…" + "*".repeat(4);
        }
        return value.substring(0, 7) + "…" + value.substring(value.length() - 4);
    }

    @Override
    @JsonValue
    public String toString() {
        return "<redacted>";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Secret s && s.value.equals(value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }
}
