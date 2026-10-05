package io.renova.web.api;

import io.renova.core.engine.PluginRegistry;
import io.renova.core.playbook.Playbook;
import io.renova.web.migration.MigrationService;
import io.renova.web.store.DataStore;
import io.renova.web.store.MigrationRecord;
import io.renova.web.store.Project;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final DataStore store;
    private final PluginRegistry registry;
    private final MigrationService migrations;

    public ProjectController(DataStore store, PluginRegistry registry, MigrationService migrations) {
        this.store = store;
        this.registry = registry;
        this.migrations = migrations;
    }

    /** @param path a directory on the server holding the project */
    public record NewProject(String name, String path) {
    }

    /** What a migration should do; omitted values use sensible defaults. */
    public record NewMigration(String playbook, Boolean ai, Boolean rag, Boolean verifyBehaviour, Boolean skipTests,
                               Integer maxAiIterations) {
    }

    @GetMapping
    public List<Project> list() {
        return store.projects();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Project add(@RequestBody NewProject request) {
        if (request.path() == null || request.path().isBlank()) {
            throw new IllegalArgumentException("Give the directory that holds the project");
        }
        Path path = Path.of(request.path().strip()).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("No such directory on the server: " + path);
        }
        Playbook playbook = registry.defaultPlaybook(path);
        String name = request.name() == null || request.name().isBlank() ? path.getFileName().toString() : request.name().strip();
        Project project = new Project(UUID.randomUUID().toString().substring(0, 8), name, path.toString(), playbook.ecosystem(),
                playbook.id(), Instant.now().toString());
        store.saveProject(project);
        return project;
    }

    @GetMapping("/{id}")
    public Project get(@PathVariable String id) {
        return project(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        if (!store.deleteProject(id)) {
            throw new NoSuchElementException("No project " + id);
        }
    }

    /** Findings, plan and automation rate (the JSON report without a migration). */
    @GetMapping(value = "/{id}/assessment", produces = MediaType.APPLICATION_JSON_VALUE)
    public String assessment(@PathVariable String id, @RequestParam(required = false) String playbook) throws Exception {
        return migrations.assess(project(id), playbook);
    }

    @GetMapping("/{id}/migrations")
    public List<MigrationRecord> migrations(@PathVariable String id) {
        project(id);
        return store.migrations().stream().filter(m -> m.projectId().equals(id)).toList();
    }

    @PostMapping("/{id}/migrations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MigrationRecord migrate(@PathVariable String id, @RequestBody(required = false) NewMigration request) throws Exception {
        NewMigration r = request == null ? new NewMigration(null, null, null, null, null, null) : request;
        int iterations = r.maxAiIterations() == null ? 3 : r.maxAiIterations();
        if (iterations < 0 || iterations > 10) {
            throw new IllegalArgumentException("maxAiIterations must be between 0 and 10");
        }
        return migrations.start(project(id), r.playbook(), new MigrationRecord.Options(
                Boolean.TRUE.equals(r.ai()), r.rag() == null || r.rag(), Boolean.TRUE.equals(r.verifyBehaviour()),
                Boolean.TRUE.equals(r.skipTests()), iterations));
    }

    private Project project(String id) {
        return store.project(id).orElseThrow(() -> new NoSuchElementException("No project " + id));
    }
}
