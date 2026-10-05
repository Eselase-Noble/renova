package io.renova.core.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
        Process process = new ProcessBuilder(command)
                .directory(workingDir.toFile())
                .redirectErrorStream(true)
                .start();
        process.getOutputStream().close();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()));
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
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

    private static String readAll(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "[output unavailable: " + e.getMessage() + "]";
        }
    }
}
