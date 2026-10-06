package io.renova.core.playbook;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A declarative migration: which rules to look for and how each one is fixed. Playbooks are data
 * (YAML), so new migration paths ship without changing the engine.
 *
 * @param settings  free-form, ecosystem-specific configuration (e.g. OpenRewrite coordinates)
 * @param knowledge curated notes retrieved for AI requests when RAG is enabled
 */
public record Playbook(String id, String name, String description, String ecosystem, String version,
                       Map<String, String> targets, Map<String, Object> settings, List<Rule> rules,
                       List<KnowledgeCard> knowledge) {

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
        knowledge = knowledge == null ? List.of() : List.copyOf(knowledge);
    }

    public Playbook(String id, String name, String description, String ecosystem, String version,
                    Map<String, String> targets, Map<String, Object> settings, List<Rule> rules) {
        this(id, name, description, ecosystem, version, targets, settings, rules, null);
    }

    /**
     * Whether this is an add-on: an optional set of changes (a library upgrade, a test framework) that is not a
     * target on its own and is combined with one, as in {@code java-to-21+junit5}. Set with {@code kind: addon}
     * in the playbook's settings.
     */
    public boolean addon() {
        return "addon".equals(settings.get("kind"));
    }

    /**
     * This playbook with the add-ons' rules and knowledge after its own. The target's rules win where an add-on
     * defines one with the same id; list settings (such as the recipe artifacts to load) are joined, and the id
     * names every part, joined by {@code +}, so it can be stored and resolved again.
     */
    public Playbook with(List<Playbook> addons) {
        if (addons.isEmpty()) {
            return this;
        }
        Map<String, Rule> mergedRules = new LinkedHashMap<>();
        rules.forEach(r -> mergedRules.put(r.id(), r));
        Map<String, KnowledgeCard> mergedKnowledge = new LinkedHashMap<>();
        knowledge.forEach(k -> mergedKnowledge.put(k.id(), k));
        Map<String, Object> mergedSettings = new LinkedHashMap<>(settings);
        StringBuilder mergedId = new StringBuilder(id);
        StringBuilder mergedName = new StringBuilder(name == null ? id : name);
        for (Playbook addon : addons) {
            if (!addon.ecosystem().equals(ecosystem)) {
                throw new IllegalArgumentException("Add-on '" + addon.id() + "' is for " + addon.ecosystem() + ", not " + ecosystem);
            }
            addon.rules().forEach(r -> mergedRules.putIfAbsent(r.id(), r));
            addon.knowledge().forEach(k -> mergedKnowledge.putIfAbsent(k.id(), k));
            addon.settings().forEach((key, value) -> {
                if (!key.equals("kind")) {
                    mergedSettings.merge(key, value, Playbook::joined);
                }
            });
            mergedId.append('+').append(addon.id());
            mergedName.append(" + ").append(addon.name() == null ? addon.id() : addon.name());
        }
        return new Playbook(mergedId.toString(), mergedName.toString(), description, ecosystem, version, targets, mergedSettings,
                List.copyOf(mergedRules.values()), List.copyOf(mergedKnowledge.values()));
    }

    /** Settings of two playbooks under one key: maps are merged key by key, lists joined without repeats, the first wins otherwise. */
    @SuppressWarnings("unchecked")
    private static Object joined(Object mine, Object theirs) {
        if (mine instanceof Map<?, ?> a && theirs instanceof Map<?, ?> b) {
            Map<String, Object> merged = new LinkedHashMap<>((Map<String, Object>) a);
            ((Map<String, Object>) b).forEach((key, value) -> merged.merge(key, value, Playbook::joined));
            return merged;
        }
        if (mine instanceof List<?> a && theirs instanceof List<?> b) {
            List<Object> merged = new java.util.ArrayList<>(a);
            b.stream().filter(item -> !merged.contains(item)).forEach(merged::add);
            return merged;
        }
        return mine;
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
