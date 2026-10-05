package io.renova.web.migration;

import io.renova.core.behaviour.DockerSandbox;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The stages of a migration as the workspace's git history: one commit per stage, each with its diff. */
public final class WorkspaceHistory {

    /** Diffs larger than this are cut, so the console stays responsive. */
    static final int MAX_DIFF_CHARS = 1_000_000;

    public record Commit(String hash, String message, int filesChanged, int insertions, int deletions) {
    }

    private WorkspaceHistory() {
    }

    public static List<Commit> commits(Path workspace) throws IOException, InterruptedException {
        DockerSandbox.Result log = git(workspace, "log", "--reverse", "--format=@@%H%x09%s", "--shortstat");
        List<Commit> commits = new ArrayList<>();
        String hash = null;
        String message = null;
        for (String line : log.output().lines().toList()) {
            if (line.startsWith("@@")) {
                if (hash != null) {
                    commits.add(new Commit(hash, message, 0, 0, 0));
                }
                String[] p = line.substring(2).split("\t", 2);
                hash = p[0];
                message = p.length > 1 ? p[1] : "";
            } else if (!line.isBlank() && hash != null) {
                commits.add(new Commit(hash, message, number(line, "file"), number(line, "insertion"), number(line, "deletion")));
                hash = null;
            }
        }
        if (hash != null) {
            commits.add(new Commit(hash, message, 0, 0, 0));
        }
        return commits;
    }

    /** The unified diff a stage made, without the build output and reports. */
    public static String diff(Path workspace, String hash) throws IOException, InterruptedException {
        if (!hash.matches("[0-9a-f]{7,40}")) {
            throw new IllegalArgumentException("Not a commit: " + hash);
        }
        String diff = git(workspace, "show", "--format=", "--patch", "--no-color", hash).output();
        return diff.length() > MAX_DIFF_CHARS ? diff.substring(0, MAX_DIFF_CHARS) + "\n… (diff cut at 1 MB)\n" : diff;
    }

    private static DockerSandbox.Result git(Path workspace, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", workspace.toString()));
        command.addAll(List.of(args));
        DockerSandbox.Result result = DockerSandbox.exec(command, null, 120);
        if (result.exit() != 0) {
            throw new IOException("git failed: " + result.output().strip());
        }
        return result;
    }

    private static int number(String shortstat, String word) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+) " + word).matcher(shortstat);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
}
