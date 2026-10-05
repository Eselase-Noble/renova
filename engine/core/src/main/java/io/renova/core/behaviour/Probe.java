package io.renova.core.behaviour;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends scenarios to both applications from inside the sandbox network, so the applications themselves
 * need no route to the outside. Uses only the JDK, so it runs from Renova's jar or classes directory in a
 * plain JRE container.
 *
 * <pre>
 * java io.renova.core.behaviour.Probe scenarios.txt responses.txt baseline=http://baseline:8080 candidate=http://candidate:8080
 * </pre>
 *
 * Input: {@code SCENARIO id full|single}, then per step {@code STEP method path}, {@code H name: value},
 * {@code B base64-body}, {@code S} (substitute captured values in the body) and {@code C name base64-spec},
 * then {@code END}. A scenario runs on the baseline, then the candidate, and with {@code full} on the
 * baseline again (to tell real differences from values that change on every request). Each run has its
 * own cookies and captured values.
 * Output: {@code @@ id:step target run status}, then {@code H name: value} lines, then
 * {@code B base64-body} or {@code E error}. Readiness: {@code @@READY target ok|timeout seconds}.
 */
public final class Probe {

    static final Duration READY_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(\\w+)}");

    private record StepLine(String method, String path, Map<String, String> headers, byte[] body, boolean substitute,
                            Map<String, String> captures) {
    }

    private record ScenarioLine(String id, boolean full, List<StepLine> steps) {
    }

    private Probe() {
    }

    public static void main(String[] args) throws Exception {
        List<ScenarioLine> scenarios = read(Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8));
        Map<String, String> targets = new LinkedHashMap<>();
        for (int i = 2; i < args.length; i++) {
            String[] kv = args[i].split("=", 2);
            targets.put(kv[0], kv[1]);
        }
        try (BufferedWriter out = Files.newBufferedWriter(Path.of(args[1]), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> target : targets.entrySet()) {
                long start = System.nanoTime();
                boolean ready = awaitReady(client(), target.getValue());
                out.write("@@READY " + target.getKey() + " " + (ready ? "ok" : "timeout") + " "
                        + (System.nanoTime() - start) / 1_000_000_000 + "\n");
            }
            for (ScenarioLine s : scenarios) {
                run(out, s, "baseline", 1, targets.get("baseline"));
                run(out, s, "candidate", 1, targets.get("candidate"));
                if (s.full()) {
                    run(out, s, "baseline", 2, targets.get("baseline"));
                }
            }
        }
    }

    private static List<ScenarioLine> read(List<String> lines) {
        List<ScenarioLine> scenarios = new ArrayList<>();
        String id = null;
        boolean full = true;
        List<StepLine> steps = new ArrayList<>();
        StepLine step = null;
        for (String line : lines) {
            if (line.startsWith("SCENARIO ")) {
                String[] p = line.split(" ");
                id = p[1];
                full = p.length < 3 || p[2].equals("full");
                steps = new ArrayList<>();
            } else if (line.startsWith("STEP ")) {
                String[] p = line.split(" ", 3);
                step = new StepLine(p[1], p[2], new LinkedHashMap<>(), new byte[0], false, new LinkedHashMap<>());
                steps.add(step);
            } else if (line.startsWith("H ") && step != null) {
                int colon = line.indexOf(':', 2);
                step.headers().put(line.substring(2, colon), line.substring(colon + 1).strip());
            } else if (line.startsWith("B ") && step != null) {
                StepLine s = step;
                step = new StepLine(s.method(), s.path(), s.headers(), Base64.getDecoder().decode(line.substring(2)), s.substitute(),
                        s.captures());
                steps.set(steps.size() - 1, step);
            } else if (line.equals("S") && step != null) {
                StepLine s = step;
                step = new StepLine(s.method(), s.path(), s.headers(), s.body(), true, s.captures());
                steps.set(steps.size() - 1, step);
            } else if (line.startsWith("C ") && step != null) {
                String[] p = line.split(" ", 3);
                step.captures().put(p[1], new String(Base64.getDecoder().decode(p[2]), StandardCharsets.UTF_8));
            } else if (line.equals("END") && id != null) {
                scenarios.add(new ScenarioLine(id, full, List.copyOf(steps)));
                step = null;
            }
        }
        return scenarios;
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5))
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
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

    /** One run of a scenario: its own cookies and captured values. */
    private static void run(BufferedWriter out, ScenarioLine scenario, String target, int run, String base)
            throws IOException, InterruptedException {
        HttpClient client = client();
        Map<String, String> captured = new HashMap<>();
        for (int i = 0; i < scenario.steps().size(); i++) {
            StepLine step = scenario.steps().get(i);
            String label = "@@ " + scenario.id() + ":" + i + " " + target + " " + run + " ";
            try {
                byte[] body = step.substitute() ? substitute(new String(step.body(), StandardCharsets.UTF_8), captured)
                        .getBytes(StandardCharsets.UTF_8) : step.body();
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + substitute(step.path(), captured)))
                        .timeout(REQUEST_TIMEOUT)
                        .method(step.method(), body.length == 0 ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(body));
                for (Map.Entry<String, String> h : step.headers().entrySet()) {
                    try {
                        request.header(h.getKey(), substitute(h.getValue(), captured));
                    } catch (IllegalArgumentException restricted) {
                        // Headers the HTTP client manages itself (Host, Content-Length, ...).
                    }
                }
                HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
                out.write(label + response.statusCode() + "\n");
                for (Map.Entry<String, List<String>> h : response.headers().map().entrySet()) {
                    out.write("H " + h.getKey() + ": " + String.join(", ", h.getValue()).replace('\n', ' ') + "\n");
                }
                out.write("B " + Base64.getEncoder().encodeToString(response.body()) + "\n");
                capture(step.captures(), response, captured);
            } catch (IOException | IllegalArgumentException e) {
                out.write(label + "-1\n");
                out.write("E " + String.valueOf(e.getMessage()).replace('\n', ' ') + "\n");
            }
        }
    }

    private static void capture(Map<String, String> specs, HttpResponse<byte[]> response, Map<String, String> captured) {
        for (Map.Entry<String, String> spec : specs.entrySet()) {
            String source;
            String regex;
            if (spec.getValue().startsWith("header:")) {
                String rest = spec.getValue().substring("header:".length());
                int colon = rest.indexOf(':');
                source = response.headers().firstValue(rest.substring(0, colon)).orElse("");
                regex = rest.substring(colon + 1);
            } else {
                source = new String(response.body(), StandardCharsets.UTF_8);
                regex = spec.getValue().startsWith("body:") ? spec.getValue().substring("body:".length()) : spec.getValue();
            }
            Matcher m = Pattern.compile(regex).matcher(source);
            if (m.find()) {
                captured.put(spec.getKey(), m.groupCount() > 0 ? m.group(1) : m.group());
            }
        }
    }

    private static String substitute(String text, Map<String, String> captured) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(captured.getOrDefault(m.group(1), m.group())));
        }
        m.appendTail(out);
        return out.toString();
    }
}
