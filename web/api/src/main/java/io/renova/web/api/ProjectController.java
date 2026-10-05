package io.renova.web.api;

import io.renova.core.engine.PluginRegistry;
import io.renova.core.playbook.Playbook;
import io.renova.web.account.Access;
import io.renova.web.account.Role;
import io.renova.web.migration.MigrationService;
import io.renova.web.store.DataStore;
import io.renova.web.store.MigrationRecord;
import io.renova.web.store.Project;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
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
    private final Access access;
    private final List<Path> roots;

    /** @param roots directories projects may be added from (renova.project-roots, comma-separated) */
    public ProjectController(DataStore store, PluginRegistry registry, MigrationService migrations, Access access,
                             @Value("${renova.project-roots:${user.home}}") String roots) {
        this.store = store;
        this.registry = registry;
        this.migrations = migrations;
        this.access = access;
        this.roots = java.util.Arrays.stream(roots.split(",")).map(String::strip).filter(r -> !r.isEmpty())
                .map(r -> Path.of(r).toAbsolutePath().normalize()).toList();
    }

    /** @param path a directory on the server holding the project */
    public record NewProject(String name, String path) {
    }

    /** What a migration should do; omitted values use sensible defaults. */
    public record NewMigration(String playbook, Boolean ai, Boolean rag, Boolean verifyBehaviour, Boolean skipTests,
                               Integer maxAiIterations) {
    }

    @GetMapping
    public List<Project> list(HttpServletRequest request) {
        String org = access.caller(request).organisationId();
        return store.projects().stream().filter(p -> org.equals(p.organisationId())).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Project add(@RequestBody NewProject body, HttpServletRequest request) throws java.io.IOException {
        Access.Caller caller = access.require(request, Role.ADMIN);
        if (body.path() == null || body.path().isBlank()) {
            throw new IllegalArgumentException("Give the directory that holds the project");
        }
        Path given = Path.of(body.path().strip()).toAbsolutePath().normalize();
        if (!Files.isDirectory(given)) {
            throw new IllegalArgumentException("No such directory on the server: " + given);
        }
        // Resolved through symbolic links, so a link cannot reach outside the allowed roots.
        Path path = given.toRealPath();
        if (roots.stream().noneMatch(root -> path.startsWith(realOrSelf(root)))) {
            throw new SecurityException("Projects must be under " + roots.stream().map(Path::toString).toList()
                    + " (renova.project-roots on the server)");
        }
        Playbook playbook = registry.defaultPlaybook(path);
        String name = body.name() == null || body.name().isBlank() ? path.getFileName().toString() : body.name().strip();
        Project project = new Project(UUID.randomUUID().toString().substring(0, 8), name, path.toString(), playbook.ecosystem(),
                playbook.id(), Instant.now().toString(), caller.organisationId());
        store.saveProject(project);
        return project;
    }

    @GetMapping("/{id}")
    public Project get(@PathVariable String id, HttpServletRequest request) {
        return project(id, access.caller(request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, HttpServletRequest request) {
        store.deleteProject(project(id, access.require(request, Role.ADMIN)).id());
    }

    /** Findings, plan and automation rate (the JSON report without a migration). */
    @GetMapping(value = "/{id}/assessment", produces = MediaType.APPLICATION_JSON_VALUE)
    public String assessment(@PathVariable String id, @RequestParam(required = false) String playbook,
                             HttpServletRequest request) throws Exception {
        return migrations.assess(project(id, access.caller(request)), playbook);
    }

    @GetMapping("/{id}/migrations")
    public List<MigrationRecord> migrations(@PathVariable String id, HttpServletRequest request) {
        project(id, access.caller(request));
        return store.migrations().stream().filter(m -> m.projectId().equals(id)).toList();
    }

    @PostMapping("/{id}/migrations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MigrationRecord migrate(@PathVariable String id, @RequestBody(required = false) NewMigration body,
                                   HttpServletRequest request) throws Exception {
        Access.Caller caller = access.require(request, Role.MEMBER);
        NewMigration r = body == null ? new NewMigration(null, null, null, null, null, null) : body;
        int iterations = r.maxAiIterations() == null ? 3 : r.maxAiIterations();
        if (iterations < 0 || iterations > 10) {
            throw new IllegalArgumentException("maxAiIterations must be between 0 and 10");
        }
        return migrations.start(project(id, caller), r.playbook(), new MigrationRecord.Options(
                Boolean.TRUE.equals(r.ai()), r.rag() == null || r.rag(), Boolean.TRUE.equals(r.verifyBehaviour()),
                Boolean.TRUE.equals(r.skipTests()), iterations), caller.user().id());
    }

    /** The project, if it belongs to the caller's organisation; others are reported as missing. */
    private Project project(String id, Access.Caller caller) {
        return store.project(id).filter(p -> caller.organisationId().equals(p.organisationId()))
                .orElseThrow(() -> new NoSuchElementException("No project " + id));
    }

    private static Path realOrSelf(Path path) {
        try {
            return path.toRealPath();
        } catch (java.io.IOException e) {
            return path;
        }
    }
}
