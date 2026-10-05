package io.renova.core.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** What an ecosystem plugin learned about a project before any rule runs. */
public record ProjectModel(Path root, String ecosystem, List<Module> modules, Map<String, Object> facts) {

    public ProjectModel {
        root = root.toAbsolutePath().normalize();
        modules = List.copyOf(modules);
        facts = Collections.unmodifiableMap(new LinkedHashMap<>(facts));
    }
}
