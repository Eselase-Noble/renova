package io.renova.core.scan;

import io.renova.core.model.Finding;
import io.renova.core.model.ProjectModel;
import io.renova.core.playbook.Rule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared view of the project's files for all detectors: one directory walk, cached file contents,
 * and glob matching on project-relative paths.
 */
public final class ScanContext {

    /** Directories that hold build output, tooling state or vendored packages, never source. */
    public static final Set<String> IGNORED_DIRS = Set.of(".git", ".svn", ".hg", "target", "build", "out",
            "node_modules", "vendor", ".idea", ".gradle", ".mvn", ".renova");

    private final ProjectModel model;
    private final List<Path> files;
    private final Map<Path, List<String>> lines = new ConcurrentHashMap<>();

    private ScanContext(ProjectModel model, List<Path> files) {
        this.model = model;
        this.files = files;
    }

    public static ScanContext of(ProjectModel model) throws IOException {
        Path root = model.root();
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return !dir.equals(root) && IGNORED_DIRS.contains(dir.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) {
                    files.add(root.relativize(file));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(null);
        return new ScanContext(model, List.copyOf(files));
    }

    public ProjectModel model() {
        return model;
    }

    public Path root() {
        return model.root();
    }

    /** All project files (relative paths) matching any of the globs. */
    public List<Path> files(List<String> globs) {
        List<PathMatcher> matchers = globs.stream().map(ScanContext::matcher).toList();
        return files.stream().filter(f -> matchers.stream().anyMatch(m -> m.matches(f))).toList();
    }

    public List<Path> files(String glob) {
        return files(List.of(glob));
    }

    /** File contents as lines; legacy files that are not UTF-8 are read as ISO-8859-1. */
    public List<String> lines(Path relative) {
        return lines.computeIfAbsent(relative, rel -> {
            Path file = root().resolve(rel);
            try {
                return Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (CharacterCodingException e) {
                try {
                    return Files.readAllLines(file, StandardCharsets.ISO_8859_1);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    public Finding finding(Rule rule, Path relative, int line, String evidence) {
        return finding(rule, toProjectPath(relative), line, evidence);
    }

    public Finding finding(Rule rule, String projectPath, int line, String evidence) {
        return finding(rule, projectPath, line, evidence, Map.of());
    }

    public Finding finding(Rule rule, String projectPath, int line, String evidence, Map<String, String> data) {
        return new Finding(rule.id(), rule.category(), rule.severity(), rule.title(), projectPath, line,
                evidence == null ? null : evidence.strip(), data);
    }

    public static String toProjectPath(Path relative) {
        return relative.toString().replace('\\', '/');
    }

    public static boolean matches(String glob, Path relative) {
        return matcher(glob).matches(relative);
    }

    /**
     * Glob matcher where a leading {@code **}{@code /} also matches files at the project root, which
     * is what playbook authors expect from "**{@code /}*.java".
     */
    static PathMatcher matcher(String glob) {
        PathMatcher main = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        if (!glob.startsWith("**/")) {
            return main;
        }
        PathMatcher rootLevel = FileSystems.getDefault().getPathMatcher("glob:" + glob.substring(3));
        return path -> main.matches(path) || rootLevel.matches(path);
    }
}
