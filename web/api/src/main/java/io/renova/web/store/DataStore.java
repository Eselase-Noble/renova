package io.renova.web.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Projects and migration records as JSON files under the data directory, so a single server needs no
 * database. Layout: {@code projects.json}, {@code migrations/ID.json}, {@code migrations/ID.log} (progress)
 * and {@code workspaces/ID/} (the migrated copies).
 */
@Component
public class DataStore {

    private final Path dataDir;
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public DataStore(@Value("${renova.data-dir}") Path dataDir) throws IOException {
        this.dataDir = dataDir.toAbsolutePath().normalize();
        Files.createDirectories(this.dataDir.resolve("migrations"));
        Files.createDirectories(this.dataDir.resolve("workspaces"));
    }

    public Path workspaceFor(String migrationId) {
        return dataDir.resolve("workspaces").resolve(migrationId);
    }

    public synchronized List<Project> projects() {
        Path file = dataDir.resolve("projects.json");
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            return json.readValue(file.toFile(), new TypeReference<List<Project>>() { });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Optional<Project> project(String id) {
        return projects().stream().filter(p -> p.id().equals(id)).findFirst();
    }

    public synchronized void saveProject(Project project) {
        List<Project> all = new ArrayList<>(projects());
        all.removeIf(p -> p.id().equals(project.id()));
        all.add(project);
        write(dataDir.resolve("projects.json"), all);
    }

    public synchronized boolean deleteProject(String id) {
        List<Project> all = new ArrayList<>(projects());
        boolean removed = all.removeIf(p -> p.id().equals(id));
        write(dataDir.resolve("projects.json"), all);
        return removed;
    }

    /** Gives projects and migrations from before accounts existed to the first organisation. */
    public synchronized void adoptUnowned(String organisationId) {
        List<Project> projects = projects().stream()
                .map(p -> p.organisationId() == null ? p.withOrganisation(organisationId) : p).toList();
        write(dataDir.resolve("projects.json"), projects);
        for (MigrationRecord m : migrations()) {
            if (m.organisationId() == null) {
                saveMigration(m.withOrganisation(organisationId));
            }
        }
    }

    public synchronized List<MigrationRecord> migrations() {
        try (Stream<Path> files = Files.list(dataDir.resolve("migrations"))) {
            List<MigrationRecord> all = new ArrayList<>();
            for (Path f : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                all.add(json.readValue(f.toFile(), MigrationRecord.class));
            }
            all.sort(Comparator.comparing(MigrationRecord::createdAt).reversed());
            return all;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized Optional<MigrationRecord> migration(String id) {
        Path file = dataDir.resolve("migrations").resolve(id + ".json");
        if (!validId(id) || !Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(file.toFile(), MigrationRecord.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void saveMigration(MigrationRecord record) {
        write(dataDir.resolve("migrations").resolve(record.id() + ".json"), record);
    }

    public void appendProgress(String migrationId, String line) {
        try {
            Files.writeString(dataDir.resolve("migrations").resolve(migrationId + ".log"), line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<String> progress(String migrationId) {
        Path file = dataDir.resolve("migrations").resolve(migrationId + ".log");
        try {
            return validId(migrationId) && Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Ids are generated by the server; anything else could reach outside the data directory. */
    public static boolean validId(String id) {
        return id != null && id.matches("[a-z0-9-]{1,64}");
    }

    private void write(Path file, Object value) {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            json.writeValue(tmp.toFile(), value);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
