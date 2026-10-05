package io.renova.core.playbook;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Typed access to a rule's detector parameters with errors that name the rule. */
public final class Params {

    private final String ruleId;
    private final Map<String, Object> raw;

    public Params(String ruleId, Map<String, Object> raw) {
        this.ruleId = ruleId;
        this.raw = raw;
    }

    public String string(String key) {
        return optString(key).orElseThrow(() -> missing(key));
    }

    public Optional<String> optString(String key) {
        Object value = raw.get(key);
        return value == null ? Optional.empty() : Optional.of(value.toString());
    }

    /** Accepts a single value or a list. */
    public List<String> strings(String key) {
        Object value = raw.get(key);
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> c) {
            return c.stream().map(String::valueOf).toList();
        }
        return List.of(value.toString());
    }

    public List<String> requiredStrings(String key) {
        List<String> values = strings(key);
        if (values.isEmpty()) {
            throw missing(key);
        }
        return values;
    }

    /** A list of nested maps, e.g. {@code provides: [{package: x, dependency: y}]}. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> maps(String key) {
        Object value = raw.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof Collection<?> c) || !c.stream().allMatch(e -> e instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Rule '" + ruleId + "': " + key + " must be a list of maps");
        }
        return c.stream().map(e -> (Map<String, Object>) e).toList();
    }

    public int intValue(String key) {
        Object value = raw.get(key);
        if (value == null) {
            throw missing(key);
        }
        return value instanceof Number n ? n.intValue() : Integer.parseInt(value.toString().trim());
    }

    private IllegalArgumentException missing(String key) {
        return new IllegalArgumentException("Rule '" + ruleId + "': detect." + key + " is required");
    }
}
