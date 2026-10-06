package io.renova.core.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Runs external tools (build tools, git, rewrite engines) and captures their combined output. */
public final class Proc {

    private Proc() {
    }

    public record Result(int exitCode, String output) {
        public boolean ok() {
            return exitCode == 0;
        }

        /** The last {@code lines} lines, which is where build tools put the useful part. */
        public String tail(int lines) {
            List<String> all = output.lines().toList();
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        }
    }

    public static Result run(List<String> command, Path workingDir, Duration timeout) throws IOException, InterruptedException {
        return run(command, workingDir, timeout, Map.of());
    }

    /** @param environment variables to set for the tool, on top of the ones this process has */
    public static Result run(List<String> command, Path workingDir, Duration timeout, Map<String, String> environment)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workingDir.toFile())
                .redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        process.getOutputStream().close();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()));
        boolean exited;
        try {
            exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // The caller was cancelled: a build left running would keep working in a workspace nobody waits for.
            kill(process);
            throw e;
        }
        if (!exited) {
            kill(process);
            return new Result(-1, output.getNow("") + "\n[timed out after " + timeout + ": " + String.join(" ", command) + "]");
        }
        return new Result(process.exitValue(), output.join());
    }

    public static boolean available(String executable) {
        try {
            return run(List.of(executable, "--version"), Path.of("."), Duration.ofSeconds(30)).ok();
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Stops the tool and whatever it started (a Maven wrapper's JVM, forked test JVMs). */
    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static String readAll(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "[output unavailable: " + e.getMessage() + "]";
        }
    }
}
