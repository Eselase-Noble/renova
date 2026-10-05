package io.renova.web.api;

import io.renova.web.account.Access;
import io.renova.web.migration.WorkspaceHistory;
import io.renova.web.store.DataStore;
import io.renova.web.store.MigrationRecord;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/migrations")
public class MigrationController {

    private final DataStore store;
    private final Access access;

    public MigrationController(DataStore store, Access access) {
        this.store = store;
        this.access = access;
    }

    /** A migration with its progress lines from {@code since} on, for polling. */
    public record Detail(MigrationRecord migration, List<String> progress, int progressTotal) {
    }

    @GetMapping
    public List<MigrationRecord> list(HttpServletRequest request) {
        String org = access.caller(request).organisationId();
        return store.migrations().stream().filter(m -> org.equals(m.organisationId())).toList();
    }

    @GetMapping("/{id}")
    public Detail get(@PathVariable String id, @RequestParam(defaultValue = "0") int since, HttpServletRequest request) {
        MigrationRecord record = migration(id, request);
        List<String> progress = store.progress(id);
        int from = Math.max(0, Math.min(since, progress.size()));
        return new Detail(record, progress.subList(from, progress.size()), progress.size());
    }

    /** The migration report (JSON): plan, stages, verification, behaviour, AI usage. */
    @GetMapping(value = "/{id}/report", produces = MediaType.APPLICATION_JSON_VALUE)
    public String report(@PathVariable String id, HttpServletRequest request) throws IOException {
        return reportFile(id, "report.json", request);
    }

    @GetMapping(value = "/{id}/report.md", produces = "text/markdown;charset=UTF-8")
    public String reportMarkdown(@PathVariable String id, HttpServletRequest request) throws IOException {
        return reportFile(id, "report.md", request);
    }

    /** Side-by-side comparison of the original and migrated application, when it ran. */
    @GetMapping(value = "/{id}/behaviour", produces = MediaType.APPLICATION_JSON_VALUE)
    public String behaviour(@PathVariable String id, HttpServletRequest request) throws IOException {
        return reportFile(id, "behaviour.json", request);
    }

    /** The stages as commits in the workspace, oldest first. */
    @GetMapping("/{id}/commits")
    public List<WorkspaceHistory.Commit> commits(@PathVariable String id, HttpServletRequest request) throws Exception {
        return WorkspaceHistory.commits(workspace(id, request));
    }

    @GetMapping(value = "/{id}/commits/{hash}/diff", produces = "text/plain;charset=UTF-8")
    public String diff(@PathVariable String id, @PathVariable String hash, HttpServletRequest request) throws Exception {
        return WorkspaceHistory.diff(workspace(id, request), hash);
    }

    private String reportFile(String id, String name, HttpServletRequest request) throws IOException {
        Path file = workspace(id, request).resolve(".renova").resolve(name);
        if (!Files.isRegularFile(file)) {
            throw new NoSuchElementException("No " + name + " for migration " + id + " (yet)");
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private Path workspace(String id, HttpServletRequest request) {
        MigrationRecord record = migration(id, request);
        Path workspace = Path.of(record.workspace());
        if (!Files.isDirectory(workspace.resolve(".git"))) {
            throw new NoSuchElementException("Migration " + id + " has no workspace yet");
        }
        return workspace;
    }

    /** The migration, if it belongs to the caller's organisation; others are reported as missing. */
    private MigrationRecord migration(String id, HttpServletRequest request) {
        String org = access.caller(request).organisationId();
        return store.migration(id).filter(m -> org.equals(m.organisationId()))
                .orElseThrow(() -> new NoSuchElementException("No migration " + id));
    }
}
