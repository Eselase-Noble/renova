package io.renova.desktop.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.renova.core.config.UserConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Migrations run from this app, newest first, kept next to the Renova user config so they survive restarts.
 * Only the summary is stored here; the details are in each migrated copy's .renova folder.
 */
public final class MigrationHistory {

    static final int MAX = 200;
    private final Path file;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * @param state          PASSED, FAILED, ERROR or CANCELLED
     * @param automationRate the share of the work that needed no human decision; null in entries written before
     *                       it was recorded, and when the migration stopped before its report
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(String projectName, String projectPath, String workspace, String state, String startedAt,
                        String finishedAt, String build, String behaviour, int aiRequests, long tokens, Double automationRate) {
    }

    public MigrationHistory() {
        this(UserConfig.defaultLocation().file().resolveSibling("desktop-migrations.json"));
    }

    public MigrationHistory(Path file) {
        this.file = file;
    }

    /** Entries whose migrated copy still exists. */
    public synchronized List<Entry> list() {
        try {
            if (!Files.exists(file)) {
                return List.of();
            }
            return json.readValue(file.toFile(), new TypeReference<List<Entry>>() { }).stream()
                    .filter(e -> Files.isDirectory(Path.of(e.workspace()))).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public synchronized void add(Entry entry) {
        List<Entry> entries = new ArrayList<>(list());
        entries.removeIf(e -> e.workspace().equals(entry.workspace()));
        entries.addFirst(entry);
        write(entries.subList(0, Math.min(MAX, entries.size())));
    }

    /** Forgets an entry; the migrated copy itself is left alone. */
    public synchronized void remove(String workspace) {
        List<Entry> entries = new ArrayList<>(list());
        entries.removeIf(e -> e.workspace().equals(workspace));
        write(entries);
    }

    private void write(List<Entry> entries) {
        try {
            Files.createDirectories(file.getParent());
            json.writeValue(file.toFile(), entries);
        } catch (IOException e) {
            // History is a convenience; never fail a migration on it.
        }
    }
}
