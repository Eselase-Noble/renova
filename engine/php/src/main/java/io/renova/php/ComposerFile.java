package io.renova.php;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a project's composer.json asks for and, where there is a composer.lock, what was installed.
 *
 * @param require    packages the project needs, each with its constraint; "php" is the PHP version itself
 * @param requireDev packages for development and tests
 * @param locked     the version of each package composer.lock records, without a leading "v"
 */
public record ComposerFile(Path file, String name, Map<String, String> require, Map<String, String> requireDev,
                           Map<String, String> locked) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static ComposerFile read(Path file) throws IOException {
        JsonNode root = JSON.readTree(Files.readString(file));
        Map<String, String> locked = new LinkedHashMap<>();
        Path lock = file.resolveSibling("composer.lock");
        if (Files.isRegularFile(lock)) {
            try {
                JsonNode lockRoot = JSON.readTree(Files.readString(lock));
                for (String section : new String[] {"packages", "packages-dev"}) {
                    lockRoot.path(section).forEach(p -> locked.put(p.path("name").asText(), Constraints.plain(p.path("version").asText())));
                }
            } catch (IOException e) {
                // A lock file that cannot be read says nothing; the constraints still do.
            }
        }
        return new ComposerFile(file, root.path("name").asText(null), section(root, "require"), section(root, "require-dev"), locked);
    }

    /** The constraint a package is required with, from either section; null when it is not required. */
    public String constraint(String name) {
        return require.containsKey(name) ? require.get(name) : requireDev.get(name);
    }

    /** The version in use: the one installed, else the lowest the constraint allows; null when not required. */
    public String version(String name) {
        if (constraint(name) == null) {
            return null;
        }
        return locked.containsKey(name) ? locked.get(name) : Constraints.lowest(constraint(name));
    }

    /** The lowest PHP version the project says it runs on; null when it does not say. */
    public String php() {
        return Constraints.lowest(require.get("php"));
    }

    private static Map<String, String> section(JsonNode root, String name) {
        Map<String, String> packages = new LinkedHashMap<>();
        root.path(name).fields().forEachRemaining(e -> packages.put(e.getKey(), e.getValue().asText()));
        return packages;
    }
}
