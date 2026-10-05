package io.renova.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Change categories shared by every ecosystem. The single-letter codes match the classification
 * used in hand-written migration guides (A–E), so generated reports read the same way.
 */
public enum Category {
    BUILD('A', "Build, platform and descriptor configuration"),
    NAMESPACE('B', "Mechanical package and namespace renames"),
    API('C', "Removed or changed APIs"),
    RUNTIME('D', "Runtime and container behaviour"),
    DEPENDENCY('E', "Dependency declarations");

    /** Order in which plan steps run: build first, behaviour-sensitive changes last. */
    public static final List<Category> EXECUTION_ORDER = List.of(BUILD, DEPENDENCY, NAMESPACE, API, RUNTIME);
    public static final Comparator<Category> BY_EXECUTION_ORDER = Comparator.comparingInt(EXECUTION_ORDER::indexOf);

    private final char code;
    private final String description;

    Category(char code, String description) {
        this.code = code;
        this.description = description;
    }

    public char code() {
        return code;
    }

    public String description() {
        return description;
    }

    @JsonValue
    public String jsonValue() {
        return String.valueOf(code);
    }

    /** Accepts either the letter code ("B") or the name ("namespace"). */
    @JsonCreator
    public static Category parse(String value) {
        String v = value.trim();
        for (Category c : values()) {
            if (v.length() == 1 && Character.toUpperCase(v.charAt(0)) == c.code) {
                return c;
            }
            if (c.name().equals(v.toUpperCase(Locale.ROOT))) {
                return c;
            }
        }
        throw new IllegalArgumentException("Unknown category '" + value + "', expected one of A-E or " + List.of(values()));
    }
}
