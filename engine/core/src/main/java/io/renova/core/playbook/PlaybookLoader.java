package io.renova.core.playbook;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PlaybookLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    private PlaybookLoader() {
    }

    public static Playbook load(InputStream in, String source) {
        try {
            Playbook playbook = YAML.readValue(in, Playbook.class);
            validate(playbook, source);
            return playbook;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read playbook " + source + ": " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid playbook " + source + ": " + e.getMessage(), e);
        }
    }

    public static Playbook load(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return load(in, file.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read playbook " + file, e);
        }
    }

    public static Playbook loadResource(ClassLoader loader, String resource) {
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("Playbook resource not found: " + resource);
            }
            return load(in, "classpath:" + resource);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void validate(Playbook playbook, String source) {
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
