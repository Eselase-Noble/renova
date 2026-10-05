package io.renova.core.behaviour;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs the original and the migrated application in containers on an internal Docker network, which
 * has no route outside the host, and sends the scenarios from a probe container on the same network.
 * Containers and the network are removed afterwards, whatever happens.
 */
public final class DockerSandbox {

    /** A plain JRE to run {@link Probe} in. */
    public static final String PROBE_IMAGE = "eclipse-temurin:21-jre";
    private static final int LOG_LINES = 80;

    /** Answers per scenario: the original's first and second, and the migrated application's. */
    public record Answers(Exchange baseline, Exchange baselineAgain, Exchange candidate) {
    }

    /**
     * @param baselineReady  whether the original answered before the timeout
     * @param candidateReady whether the migrated application answered before the timeout
     */
    public record Run(boolean baselineReady, boolean candidateReady, Map<String, Answers> answers,
                      String baselineLog, String candidateLog) {
    }

    private DockerSandbox() {
    }

    /** Whether a Docker daemon is reachable. */
    public static boolean available() {
        try {
            return exec(List.of("docker", "info", "--format", "{{.ServerVersion}}"), null, 30).exit() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    public static Run run(BehaviourRunner.Deployments deployments, List<Scenario> scenarios, Path workDir,
                          Consumer<String> progress) throws IOException, InterruptedException {
        String id = "renova-bv-" + HexFormat.of().formatHex(new SecureRandom().generateSeed(4));
        Files.createDirectories(workDir);
        StringBuilder lines = new StringBuilder();
        scenarios.forEach(s -> lines.append(s.id()).append('\t').append(s.method()).append('\t').append(s.path()).append('\n'));
        Files.writeString(workDir.resolve("scenarios.txt"), lines);
        Path responses = workDir.resolve("responses.txt");
        Files.deleteIfExists(responses);

        List<String> containers = new ArrayList<>();
        check(exec(List.of("docker", "network", "create", "--internal", id), null, 60), "create the sandbox network");
        try {
            progress.accept("Starting the original (" + deployments.baseline().platform() + ") and the migrated application ("
                    + deployments.candidate().platform() + ") in containers");
            start(id, "baseline", deployments.baseline(), containers);
            start(id, "candidate", deployments.candidate(), containers);

            Path probeClasses = probeClasspath();
            String mounted = Files.isDirectory(probeClasses) ? "/renova/classes" : "/renova/renova.jar";
            List<String> probe = new ArrayList<>(List.of("docker", "run", "--rm", "--name", id + "-probe", "--network", id));
            probe.addAll(userFlag());
            probe.addAll(List.of("-v", probeClasses + ":" + mounted + ":ro", "-v", workDir.toAbsolutePath() + ":/work",
                    PROBE_IMAGE, "java", "-cp", mounted, Probe.class.getName(), "/work/scenarios.txt", "/work/responses.txt",
                    "baseline=http://baseline:" + deployments.baseline().port() + deployments.baseline().contextPath(),
                    "candidate=http://candidate:" + deployments.candidate().port() + deployments.candidate().contextPath()));
            progress.accept("Sending " + scenarios.size() + " request(s) to both applications");
            check(exec(probe, null, Probe.READY_TIMEOUT.toSeconds() * 2 + 60L * scenarios.size()), "run the probe");

            Run parsed = parse(Files.readAllLines(responses, StandardCharsets.UTF_8));
            return new Run(parsed.baselineReady(), parsed.candidateReady(), parsed.answers(),
                    logs(id + "-baseline"), logs(id + "-candidate"));
        } finally {
            for (String container : containers) {
                exec(List.of("docker", "rm", "-f", container), null, 60);
            }
            exec(List.of("docker", "network", "rm", id), null, 60);
        }
    }

    private static void start(String id, String alias, AppDeployment app, List<String> containers)
            throws IOException, InterruptedException {
        String name = id + "-" + alias;
        List<String> cmd = new ArrayList<>(List.of("docker", "run", "-d", "--name", name, "--network", id,
                "--network-alias", alias));
        app.mounts().forEach((host, container) -> cmd.addAll(List.of("-v", host.toAbsolutePath() + ":" + container + ":ro")));
        cmd.add(app.image());
        containers.add(name);
        check(exec(cmd, null, 300), "start the " + alias + " container from " + app.image());
    }

    static Run parse(List<String> lines) {
        boolean baselineReady = false;
        boolean candidateReady = false;
        Map<String, Exchange[]> byScenario = new LinkedHashMap<>();
        String scenario = null;
        int slot = -1;
        int status = 0;
        Map<String, String> headers = new LinkedHashMap<>();
        for (String line : lines) {
            if (line.startsWith("@@READY ")) {
                String[] p = line.split(" ");
                if (p[1].equals("baseline")) {
                    baselineReady = p[2].equals("ok");
                } else {
                    candidateReady = p[2].equals("ok");
                }
            } else if (line.startsWith("@@ ")) {
                String[] p = line.split(" ");
                scenario = p[1];
                slot = p[2].equals("candidate") ? 2 : p[3].equals("1") ? 0 : 1;
                status = Integer.parseInt(p[4]);
                headers = new LinkedHashMap<>();
            } else if (line.startsWith("H ") && scenario != null) {
                int colon = line.indexOf(':', 2);
                headers.put(line.substring(2, colon).toLowerCase(java.util.Locale.ROOT), line.substring(colon + 1).strip());
            } else if ((line.startsWith("B ") || line.equals("B")) && scenario != null) {
                byScenario.computeIfAbsent(scenario, k -> new Exchange[3])[slot] =
                        new Exchange(status, headers, Base64.getDecoder().decode(line.length() > 2 ? line.substring(2) : ""), null);
            } else if (line.startsWith("E ") && scenario != null) {
                byScenario.computeIfAbsent(scenario, k -> new Exchange[3])[slot] = Exchange.failed(line.substring(2));
            }
        }
        Map<String, Answers> answers = new LinkedHashMap<>();
        byScenario.forEach((k, v) -> answers.put(k, new Answers(orFailed(v[0]), v[1], orFailed(v[2]))));
        return new Run(baselineReady, candidateReady, answers, null, null);
    }

    private static Exchange orFailed(Exchange e) {
        return e == null ? Exchange.failed("no answer recorded") : e;
    }

    private static String logs(String container) {
        try {
            String all = exec(List.of("docker", "logs", container), null, 60).output();
            List<String> lines = all.lines().toList();
            return String.join("\n", lines.subList(Math.max(0, lines.size() - LOG_LINES), lines.size()));
        } catch (IOException | InterruptedException e) {
            return "(logs unavailable: " + e.getMessage() + ")";
        }
    }

    /** Where the probe's class is loaded from: Renova's jar, or a classes directory in development. */
    static Path probeClasspath() {
        try {
            return Path.of(Probe.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot locate Renova's classes for the probe", e);
        }
    }

    /** Runs the probe as the current user, so the files it writes belong to them. */
    private static List<String> userFlag() {
        try {
            Result uid = exec(List.of("id", "-u"), null, 10);
            Result gid = exec(List.of("id", "-g"), null, 10);
            if (uid.exit() == 0 && gid.exit() == 0) {
                return List.of("--user", uid.output().strip() + ":" + gid.output().strip());
            }
        } catch (IOException | InterruptedException e) {
            // Not a Unix system: run as the image's user.
        }
        return List.of();
    }

    public record Result(int exit, String output) {
    }

    public static Result exec(List<String> command, Path dir, long timeoutSeconds) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        if (dir != null) {
            pb.directory(dir.toFile());
        }
        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try {
                output.append(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                // The process ended; keep what was read.
            }
        });
        reader.start();
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            reader.join(5000);
            return new Result(-1, output + "\n(timed out after " + timeoutSeconds + "s)");
        }
        reader.join();
        return new Result(process.exitValue(), output.toString());
    }

    private static void check(Result result, String what) throws IOException {
        if (result.exit() != 0) {
            String out = result.output().strip();
            throw new IOException("Could not " + what + ": " + (out.length() > 600 ? out.substring(out.length() - 600) : out));
        }
    }
}
