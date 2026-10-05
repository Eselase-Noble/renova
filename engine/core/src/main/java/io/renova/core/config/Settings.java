package io.renova.core.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Configuration resolved from several sources in priority order, e.g. command-line flags, then
 * environment variables, then a user config file. Each value remembers where it came from, so
 * users can see why a setting has the value it has.
 */
public final class Settings {

    /** One named source of key/value pairs. */
    public record Source(String name, Map<String, String> values) {
        public Source {
            values = Map.copyOf(values);
        }
    }

    /** A resolved value and the source that supplied it. */
    public record Value(String key, String value, String source) {
    }

    private final List<Source> sources;

    /** @param sources highest priority first */
    public Settings(List<Source> sources) {
        this.sources = List.copyOf(sources);
    }

    /** Looks up the first of {@code keys} that any source defines, trying sources in priority order. */
    public Optional<Value> find(String... keys) {
        for (Source source : sources) {
            for (String key : keys) {
                String value = source.values().get(key);
                if (value != null && !value.isBlank()) {
                    return Optional.of(new Value(key, value.strip(), source.name()));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<String> get(String... keys) {
        return find(keys).map(Value::value);
    }

    public List<Source> sources() {
        return new ArrayList<>(sources);
    }
}
