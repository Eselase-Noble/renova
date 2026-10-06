package io.renova.web.api;

import io.renova.core.engine.PluginRegistry;
import io.renova.web.account.Access;
import io.renova.web.account.Role;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** What this server is and can do, and the folders projects may be added from. */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private static final int MAX_ENTRIES = 500;

    private final PluginRegistry registry;
    private final Access access;
    private final List<Path> roots;
    private final int parallel;
    private final String version;
    private final String dataDir;
    private final boolean localMode;

    public SystemController(PluginRegistry registry, Access access, ObjectProvider<BuildProperties> build,
                            @Value("${renova.project-roots:${user.home}}") String roots,
                            @Value("${renova.parallel-migrations:1}") int parallel,
                            @Value("${renova.data-dir}") Path dataDir, @Value("${renova.mode:server}") String mode) {
        this.dataDir = dataDir.toAbsolutePath().normalize().toString();
        this.localMode = "local".equalsIgnoreCase(mode);
        this.registry = registry;
        this.access = access;
        this.roots = Arrays.stream(roots.split(",")).map(String::strip).filter(r -> !r.isEmpty())
                .map(r -> real(Path.of(r).toAbsolutePath().normalize())).toList();
        this.parallel = Math.max(1, parallel);
        // Written by the build; absent when the API is started from an IDE.
        BuildProperties properties = build.getIfAvailable();
        this.version = properties == null ? "development" : properties.getVersion();
    }

    public record Ecosystem(String id, String name) {
    }

    /** @param dataDir where projects are registered and migrated copies are kept, on this server */
    public record Info(String version, String java, List<Ecosystem> ecosystems, List<String> aiProviders, int playbooks,
                       int parallelMigrations, List<String> projectRoots, String dataDir, boolean localMode) {
    }

    /** @param project whether the folder holds a build file an installed ecosystem recognises */
    public record Entry(String name, String path, boolean project) {
    }

    /** @param parent the folder above, or null at an allowed root */
    public record Listing(String path, String parent, boolean project, List<Entry> entries, boolean truncated) {
    }

    @GetMapping
    public Info info(HttpServletRequest request) {
        access.caller(request);
        return new Info(version, Runtime.version().toString(),
                registry.plugins().stream().map(p -> new Ecosystem(p.id(), p.displayName())).toList(),
                registry.aiProviders().stream().map(p -> p.name()).toList(), registry.playbooks().size(), parallel,
                roots.stream().map(Path::toString).toList(), dataDir, localMode);
    }

    /**
     * The folders inside {@code path}, for choosing a project without typing its path. Only folders under
     * {@code renova.project-roots} are listed, after resolving symbolic links; without a path, the roots.
     */
    @GetMapping("/directories")
    public Listing directories(@RequestParam(required = false) String path, HttpServletRequest request) throws IOException {
        access.require(request, Role.ADMIN);
        if (path == null || path.isBlank()) {
            return new Listing(null, null, false, roots.stream().filter(Files::isDirectory)
                    .map(r -> new Entry(r.toString(), r.toString(), isProject(r))).toList(), false);
        }
        Path given = Path.of(path.strip()).toAbsolutePath().normalize();
        if (!Files.isDirectory(given)) {
            throw new IllegalArgumentException("No such directory on the server: " + given);
        }
        Path dir = given.toRealPath();
        if (roots.stream().noneMatch(dir::startsWith)) {
            throw new SecurityException("Projects must be under " + roots.stream().map(Path::toString).toList()
                    + " (renova.project-roots on the server)");
        }
        List<Path> folders;
        try (Stream<Path> children = Files.list(dir)) {
            folders = children.filter(c -> Files.isDirectory(c) && !c.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing(c -> c.getFileName().toString().toLowerCase())).toList();
        }
        List<Entry> entries = folders.stream().limit(MAX_ENTRIES)
                .map(c -> new Entry(c.getFileName().toString(), c.toString(), isProject(c))).toList();
        String parent = roots.contains(dir) || dir.getParent() == null || roots.stream().noneMatch(dir.getParent()::startsWith)
                ? null : dir.getParent().toString();
        return new Listing(dir.toString(), parent, isProject(dir), entries, folders.size() > MAX_ENTRIES);
    }

    private boolean isProject(Path dir) {
        // Marker files only: supports() may read the whole tree, far too slow for every folder in a listing.
        return registry.plugins().stream().flatMap(p -> p.projectMarkers().stream())
                .anyMatch(marker -> Files.isRegularFile(dir.resolve(marker)));
    }

    private static Path real(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path;
        }
    }
}
