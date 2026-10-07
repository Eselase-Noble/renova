package io.renova.core.workspace;

import io.renova.core.util.Proc;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A copy of the project that the migration edits. The source is never modified. Every stage is a
 * git commit, so the result can be reviewed and audited step by step, and any stage reverted.
 */
public final class Workspace {

    private static final Set<String> NOT_COPIED = Set.of(".git", ".svn", ".hg", "target", "node_modules", "vendor",
            ".idea", ".gradle", ".renova");
    private static final Duration GIT_TIMEOUT = Duration.ofMinutes(5);

    private final Path root;
    private final boolean versioned;

    private Workspace(Path root, boolean versioned) {
        this.root = root;
        this.versioned = versioned;
    }

    public static Workspace create(Path source, Path target) throws IOException, InterruptedException {
        Path src = source.toAbsolutePath().normalize();
        Path dst = target.toAbsolutePath().normalize();
        if (dst.startsWith(src)) {
            throw new IllegalArgumentException("Output directory must be outside the project: " + dst);
        }
        if (Files.exists(dst)) {
            try (Stream<Path> entries = Files.list(dst)) {
                if (entries.findAny().isPresent()) {
                    throw new IllegalArgumentException("Output directory is not empty: " + dst);
                }
            }
        }
        copyTree(src, dst);
        Files.createDirectories(dst.resolve(".renova"));

        boolean versioned = Proc.available("git");
        Workspace ws = new Workspace(dst, versioned);
        if (versioned) {
            ws.git("init", "-q");
            ws.git("config", "user.name", "Renova");
            ws.git("config", "user.email", "renova@localhost");
            // Build output is never part of a stage: Maven's target, Gradle's .gradle and build (but a source
            // package that happens to be called build is kept).
            Files.writeString(dst.resolve(".git/info/exclude"), ".renova/\ntarget/\n.gradle/\nbuild/\n!**/src/**/build/\nvendor/\n");
            ws.commitAll("renova: baseline (unmodified copy of " + src.getFileName() + ")");
        }
        return ws;
    }

    /** An existing workspace made by {@link #create}, e.g. to verify it again. */
    public static Workspace open(Path root) {
        Path dir = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(dir.resolve(".renova"))) {
            throw new IllegalArgumentException("Not a Renova workspace (no .renova directory): " + dir);
        }
        return new Workspace(dir, Files.isDirectory(dir.resolve(".git")));
    }

    public Path root() {
        return root;
    }

    /** Scratch area for reports and logs, excluded from version control. */
    public Path lcDir() {
        return root.resolve(".renova");
    }

    public boolean versioned() {
        return versioned;
    }

    /** Commits all changes; returns false when there was nothing to commit. */
    public boolean commitAll(String message) throws IOException, InterruptedException {
        if (!versioned) {
            return false;
        }
        git("add", "-A");
        if (git("status", "--porcelain").output().isBlank()) {
            return false;
        }
        git("commit", "-q", "--no-verify", "-m", message);
        return true;
    }

    /** Short change statistics since the given commit-ish, e.g. "HEAD~1". */
    public String diffStat(String since) throws IOException, InterruptedException {
        return versioned ? git("diff", "--shortstat", since).output().strip() : "";
    }

    /** True when a migration stage changed or added the file: it differs from the baseline commit. */
    public boolean changedSinceBaseline(String file) throws IOException, InterruptedException {
        if (!versioned) {
            return false;
        }
        String baseline = git("rev-list", "--max-parents=0", "HEAD").output().strip();
        return !git("diff", "--name-only", baseline, "HEAD", "--", file).output().isBlank();
    }

    private Proc.Result git(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Proc.Result result = Proc.run(cmd, root, GIT_TIMEOUT);
        if (!result.ok()) {
            throw new IOException("git " + String.join(" ", args) + " failed: " + result.tail(20));
        }
        return result;
    }

    private static void copyTree(Path src, Path dst) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(src) && NOT_COPIED.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, dst.resolve(src.relativize(file).toString()),
                        StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
