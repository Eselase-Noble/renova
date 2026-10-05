package io.renova.core.playbook;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A declarative migration: which rules to look for and how each one is fixed. Playbooks are data
 * (YAML), so new migration paths ship without changing the engine.
 *
 * @param settings free-form, ecosystem-specific configuration (e.g. OpenRewrite coordinates)
 */
public record Playbook(String id, String name, String description, String ecosystem, String version,
                       Map<String, String> targets, Map<String, Object> settings, List<Rule> rules) {

    public Playbook {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Playbook needs an id");
        }
        if (ecosystem == null || ecosystem.isBlank()) {
            throw new IllegalArgumentException("Playbook '" + id + "' needs an ecosystem");
        }
        targets = targets == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(targets));
        settings = settings == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(settings));
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /** Looks up a nested setting by dotted path, e.g. {@code "openrewrite.plugin"}. */
    public Object setting(String dottedPath) {
        Object current = settings;
        for (String part : dottedPath.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
        }
        return current;
    }
}
