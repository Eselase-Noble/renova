package io.renova.desktop.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.renova.core.config.UserConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Projects opened recently, newest first, next to the Renova user config. */
public final class RecentProjects {

    static final int MAX = 12;
    private final Path file;
    private final ObjectMapper json = new ObjectMapper();

    public record Entry(String path, String name, String openedAt) {
    }

    public RecentProjects() {
        this(UserConfig.defaultLocation().file().resolveSibling("desktop-recent.json"));
    }

    public RecentProjects(Path file) {
        this.file = file;
    }

    public List<Entry> list() {
        try {
            if (!Files.exists(file)) {
                return List.of();
            }
            return json.readValue(file.toFile(), new TypeReference<List<Entry>>() { }).stream()
                    .filter(e -> Files.isDirectory(Path.of(e.path()))).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public void opened(Path project) {
        List<Entry> entries = new ArrayList<>(list());
        String path = project.toAbsolutePath().normalize().toString();
        entries.removeIf(e -> e.path().equals(path));
        entries.addFirst(new Entry(path, project.getFileName().toString(), Instant.now().toString()));
        try {
            Files.createDirectories(file.getParent());
            json.writeValue(file.toFile(), entries.subList(0, Math.min(MAX, entries.size())));
        } catch (IOException e) {
            // Remembering recent projects is a convenience; never fail on it.
        }
    }
}
