package io.renova.core.behaviour;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends the scenarios to both applications from inside the sandbox network, so the applications
 * themselves need no route to the outside. Uses only the JDK, so it runs from Renova's jar or classes
 * directory in a plain JRE container.
 *
 * <pre>
 * java io.renova.core.behaviour.Probe scenarios.txt responses.txt baseline=http://baseline:8080 candidate=http://candidate:8080
 * </pre>
 *
 * Scenario lines are {@code id TAB method TAB path}. For every scenario the baseline is asked twice
 * (to tell real differences from values that change on every request) and the candidate once.
 * Output records: {@code @@ id target run status}, then {@code H name: value} lines, then
 * {@code B base64-body} or {@code E error}. Readiness: {@code @@READY target ok|timeout seconds}.
 */
public final class Probe {

    static final Duration READY_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private Probe() {
    }

    public static void main(String[] args) throws Exception {
        List<String[]> scenarios = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                scenarios.add(line.split("\t", 3));
            }
        }
        Map<String, String> targets = new LinkedHashMap<>();
        for (int i = 2; i < args.length; i++) {
            String[] kv = args[i].split("=", 2);
            targets.put(kv[0], kv[1]);
        }
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5)).build();
        try (BufferedWriter out = Files.newBufferedWriter(Path.of(args[1]), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> target : targets.entrySet()) {
                long start = System.nanoTime();
                boolean ready = awaitReady(client, target.getValue());
                out.write("@@READY " + target.getKey() + " " + (ready ? "ok" : "timeout") + " "
                        + (System.nanoTime() - start) / 1_000_000_000 + "\n");
            }
            for (String[] s : scenarios) {
                record(out, client, s, "baseline", 1, targets.get("baseline"));
                record(out, client, s, "candidate", 1, targets.get("candidate"));
                record(out, client, s, "baseline", 2, targets.get("baseline"));
            }
        }
    }

    private static boolean awaitReady(HttpClient client, String base) throws InterruptedException {
        long deadline = System.nanoTime() + READY_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                client.send(HttpRequest.newBuilder(URI.create(base + "/")).timeout(REQUEST_TIMEOUT).build(),
                        HttpResponse.BodyHandlers.discarding());
                return true;
            } catch (IOException e) {
                Thread.sleep(1000);
            }
        }
        return false;
    }

    private static void record(BufferedWriter out, HttpClient client, String[] scenario, String target, int run, String base)
            throws IOException, InterruptedException {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + scenario[2])).timeout(REQUEST_TIMEOUT)
                    .method(scenario[1], HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            out.write("@@ " + scenario[0] + " " + target + " " + run + " " + response.statusCode() + "\n");
            for (Map.Entry<String, List<String>> h : response.headers().map().entrySet()) {
                out.write("H " + h.getKey() + ": " + String.join(", ", h.getValue()).replace('\n', ' ') + "\n");
            }
            out.write("B " + Base64.getEncoder().encodeToString(response.body()) + "\n");
        } catch (IOException | IllegalArgumentException e) {
            out.write("@@ " + scenario[0] + " " + target + " " + run + " -1\n");
            out.write("E " + String.valueOf(e.getMessage()).replace('\n', ' ') + "\n");
        }
    }
}
