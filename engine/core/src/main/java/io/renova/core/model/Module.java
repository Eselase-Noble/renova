package io.renova.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A buildable unit of the project (a Maven module, a Gradle project, an npm package, ...).
 *
 * @param path      project-relative directory, "." for the root
 * @param buildFile project-relative path of the build descriptor
 * @param facts     ecosystem-specific facts, e.g. "javaVersion" or "dependencies"
 */
public record Module(String name, String path, String buildFile, Map<String, Object> facts) {

    public Module {
        facts = Collections.unmodifiableMap(new LinkedHashMap<>(facts));
    }

    public Object fact(String key) {
        return facts.get(key);
    }
}
