package io.renova.core.playbook;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PlaybookLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    private PlaybookLoader() {
    }

    /** Resolves the files a playbook includes: relative to the playbook, or {@code classpath:} for a bundled pack. */
    private interface Includes {
        InputStream open(String reference) throws IOException;
    }

    public static Playbook load(InputStream in, String source) {
        return load(in, source, reference -> classpath(PlaybookLoader.class.getClassLoader(), reference, source));
    }

    /**
     * A playbook may {@code include} rule packs: files with {@code rules} and {@code knowledge} that several
     * playbooks share, so a migration path is a short list of the packs it is made of. The playbook's own rules
     * come first, then the included ones in the order of the list; a rule or knowledge card the playbook defines
     * itself replaces an included one with the same id, so a path can adjust a shared rule without copying the pack.
     */
    private static Playbook load(InputStream in, String source, Includes includes) {
        try {
            ObjectNode tree = object(YAML.readTree(in), source);
            JsonNode include = tree.remove("include");
            if (include != null) {
                Map<String, JsonNode> rules = new LinkedHashMap<>();
                Map<String, JsonNode> knowledge = new LinkedHashMap<>();
                for (JsonNode reference : include) {
                    try (InputStream pack = includes.open(reference.asText())) {
                        ObjectNode packTree = object(YAML.readTree(pack), reference.asText());
                        packTree.fieldNames().forEachRemaining(field -> {
                            if (!field.equals("rules") && !field.equals("knowledge")) {
                                throw new IllegalArgumentException("rule pack " + reference.asText() + " may only hold rules and "
                                        + "knowledge, not '" + field + "'");
                            }
                        });
                        collect(packTree.path("rules"), rules, "rule", reference.asText());
                        collect(packTree.path("knowledge"), knowledge, "knowledge card", reference.asText());
                    }
                }
                tree.set("rules", merged(tree.path("rules"), rules));
                tree.set("knowledge", merged(tree.path("knowledge"), knowledge));
            }
            Playbook playbook = YAML.treeToValue(tree, Playbook.class);
            validate(playbook, source);
            return playbook;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read playbook " + source + ": " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid playbook " + source + ": " + e.getMessage(), e);
        }
    }

    /** The playbook's own items first, in its order, then the included ones it does not replace. */
    private static JsonNode merged(JsonNode own, Map<String, JsonNode> included) {
        var all = YAML.createArrayNode();
        own.forEach(item -> {
            all.add(item);
            included.remove(item.path("id").asText());
        });
        return all.addAll(included.values());
    }

    private static ObjectNode object(JsonNode node, String source) {
        if (!(node instanceof ObjectNode object)) {
            throw new IllegalArgumentException(source + " is not a YAML mapping");
        }
        return object;
    }

    /** Within the packs, an id may appear once: two packs defining the same rule is a mistake, not an override. */
    private static void collect(JsonNode items, Map<String, JsonNode> into, String kind, String pack) {
        for (JsonNode item : items) {
            String id = item.path("id").asText();
            if (into.putIfAbsent(id, item) != null) {
                throw new IllegalArgumentException("duplicate " + kind + " id '" + id + "' in included pack " + pack);
            }
        }
    }

    private static InputStream classpath(ClassLoader loader, String reference, String source) throws IOException {
        String resource = reference.startsWith("classpath:") ? reference.substring("classpath:".length()) : reference;
        InputStream in = loader.getResourceAsStream(resource);
        if (in == null) {
            throw new IOException("included pack not found: " + reference + " (from " + source + ")");
        }
        return in;
    }

    /** A playbook file: includes are files beside it, or bundled packs named {@code classpath:…}. */
    public static Playbook load(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return load(in, file.toString(), reference -> reference.startsWith("classpath:")
                    ? classpath(Thread.currentThread().getContextClassLoader() == null ? PlaybookLoader.class.getClassLoader()
                            : Thread.currentThread().getContextClassLoader(), reference, file.toString())
                    : Files.newInputStream(file.resolveSibling(reference)));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read playbook " + file, e);
        }
    }

    /** A bundled playbook: includes are resources relative to it, or {@code classpath:} paths from the root. */
    public static Playbook loadResource(ClassLoader loader, String resource) {
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("Playbook resource not found: " + resource);
            }
            String folder = resource.contains("/") ? resource.substring(0, resource.lastIndexOf('/') + 1) : "";
            return load(in, "classpath:" + resource, reference -> classpath(loader,
                    reference.startsWith("classpath:") ? reference : folder + reference, resource));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void validate(Playbook playbook, String source) {
        Set<String> cards = new HashSet<>();
        for (KnowledgeCard card : playbook.knowledge()) {
            if (!cards.add(card.id())) {
                throw new IllegalArgumentException("duplicate knowledge card id '" + card.id() + "' in " + source);
            }
        }
        Set<String> ids = new HashSet<>();
        for (Rule rule : playbook.rules()) {
            if (!ids.add(rule.id())) {
                throw new IllegalArgumentException("duplicate rule id '" + rule.id() + "' in " + source);
            }
            FixSpec fix = rule.fix();
            if (FixSpec.RECIPE.equals(fix.strategy()) && fix.recipes().isEmpty()) {
                throw new IllegalArgumentException("rule '" + rule.id() + "' uses strategy recipe without recipes");
            }
            if (FixSpec.REPLACE.equals(fix.strategy())) {
                List<Map<String, Object>> pairs = fix.params(rule.id()).maps("replacements");
                boolean single = fix.find() != null && fix.replace() != null;
                if (fix.include() == null || (!single && pairs.isEmpty())
                        || pairs.stream().anyMatch(p -> p.get("find") == null || p.get("replace") == null)) {
                    throw new IllegalArgumentException("rule '" + rule.id()
                            + "' uses strategy replace without include and find/replace (or params.replacements)");
                }
            }
        }
    }
}
