package io.renova.core.behaviour;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scenarios written for an application ({@code renova-scenarios.yaml} in the project root), sent to
 * both versions in addition to the entry points found in the code.
 *
 * <pre>
 * database:                                  # optional: compare what each version writes
 *   init: db/schema.sql                      # seed, relative to this file; each version gets a fresh copy
 *   ignoreColumns: [id, created_at]          # values that legitimately differ
 *   app:                                     # system properties for the app; ${host} is its own database
 *     claims.db.url: jdbc:postgresql://${host}:5432/app
 * scenarios:
 *   - id: file-a-claim
 *     steps:
 *       - { method: POST, path: /claims, form: { holder: Ama Mensah, amount: "1520.75" },
 *           capture: { claim: "header:Location:/claims/(\\d+)" } }
 *       - { path: "/claims/${claim}" }
 *       - { method: POST, path: /items/import, multipart: { file: { filename: items.csv, content: "sku,qty\n" } } }
 * </pre>
 *
 * The database is PostgreSQL with user, password and database {@code renova}/{@code renova}/{@code app}.
 */
public record ScenarioFile(Database database, List<ScenarioSpec> scenarios) {

    public static final String DEFAULT_NAME = "renova-scenarios.yaml";
    static final String BOUNDARY = "----RenovaScenarioBoundary7MA4YWxkTrZu0gW";

    /**
     * @param image         PostgreSQL image; default {@code postgres:16-alpine}
     * @param init          SQL run when the database is created, relative to the scenario file
     * @param ignoreColumns columns left out when comparing rows
     * @param app           system properties for the application; {@code ${host}} is replaced with its database's host
     */
    public record Database(String image, String init, List<String> ignoreColumns, Map<String, String> app) {
        public Database {
            image = image == null ? "postgres:16-alpine" : image;
            ignoreColumns = ignoreColumns == null ? List.of() : List.copyOf(ignoreColumns);
            app = app == null ? Map.of() : Map.copyOf(app);
        }
    }

    public record ScenarioSpec(String id, String description, List<StepSpec> steps) {
    }

    /**
     * Exactly one of {@code form}, {@code json}, {@code body} or {@code multipart} may be given.
     *
     * @param capture name to {@code body:REGEX} or {@code header:NAME:REGEX}
     */
    public record StepSpec(String method, String path, Map<String, String> headers, Map<String, String> form, Object json,
                           String body, String contentType, Map<String, Object> multipart, Map<String, String> capture,
                           List<String> ignore) {
    }

    /** A file part of a multipart body: inline {@code content} or a {@code file} relative to the scenario file. */
    public record FilePart(String filename, String content, String file, String contentType) {
    }

    public static ScenarioFile load(Path file) throws IOException {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory()).configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        ScenarioFile parsed = yaml.readValue(file.toFile(), ScenarioFile.class);
        return new ScenarioFile(parsed.database(), parsed.scenarios() == null ? List.of() : parsed.scenarios());
    }

    /** The scenarios as steps ready to send; ids are prefixed to keep them apart from discovered ones. */
    public List<Scenario> toScenarios(Path baseDir) throws IOException {
        List<Scenario> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        ObjectMapper json = new ObjectMapper();
        for (ScenarioSpec spec : scenarios) {
            if (spec.id() == null || !spec.id().matches("[\\w.-]+") || !ids.add(spec.id())) {
                throw new IllegalArgumentException(DEFAULT_NAME + ": each scenario needs a unique id of letters, digits, '.', '_' or '-'");
            }
            if (spec.steps() == null || spec.steps().isEmpty()) {
                throw new IllegalArgumentException(DEFAULT_NAME + ": scenario " + spec.id() + " has no steps");
            }
            List<Step> steps = new ArrayList<>();
            for (StepSpec s : spec.steps()) {
                Map<String, String> headers = new LinkedHashMap<>(s.headers() == null ? Map.of() : s.headers());
                long bodies = java.util.stream.Stream.of(s.form(), s.json(), s.body(), s.multipart()).filter(java.util.Objects::nonNull).count();
                if (bodies > 1) {
                    throw new IllegalArgumentException(DEFAULT_NAME + ": scenario " + spec.id() + ": give only one of form, json, body or multipart");
                }
                byte[] body = null;
                boolean substitute = false;
                if (s.form() != null) {
                    StringBuilder encoded = new StringBuilder();
                    s.form().forEach((k, v) -> encoded.append(encoded.isEmpty() ? "" : "&")
                            .append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
                            .append(URLEncoder.encode(v, StandardCharsets.UTF_8).replace("%24%7B", "${").replace("%7D", "}")));
                    body = encoded.toString().getBytes(StandardCharsets.UTF_8);
                    headers.putIfAbsent("Content-Type", "application/x-www-form-urlencoded");
                    substitute = true;
                } else if (s.json() != null) {
                    body = json.writeValueAsBytes(s.json());
                    headers.putIfAbsent("Content-Type", "application/json");
                    substitute = true;
                } else if (s.body() != null) {
                    body = s.body().getBytes(StandardCharsets.UTF_8);
                    headers.putIfAbsent("Content-Type", s.contentType() == null ? "text/plain" : s.contentType());
                    substitute = true;
                } else if (s.multipart() != null) {
                    body = multipart(s.multipart(), baseDir, json);
                    headers.putIfAbsent("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
                }
                String method = s.method() != null ? s.method() : body == null ? "GET" : "POST";
                steps.add(new Step(method, s.path(), headers, body, substitute, s.capture(), s.ignore()));
            }
            result.add(new Scenario("file." + spec.id(), spec.description() == null ? DEFAULT_NAME
                    : DEFAULT_NAME + ": " + spec.description(), steps));
        }
        return result;
    }

    private static byte[] multipart(Map<String, Object> parts, Path baseDir, ObjectMapper json) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, Object> part : parts.entrySet()) {
            out.writeBytes(("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.UTF_8));
            if (part.getValue() instanceof Map<?, ?>) {
                FilePart file = json.convertValue(part.getValue(), FilePart.class);
                byte[] content = file.file() != null ? Files.readAllBytes(baseDir.resolve(file.file()))
                        : (file.content() == null ? "" : file.content()).getBytes(StandardCharsets.UTF_8);
                String filename = file.filename() != null ? file.filename()
                        : file.file() != null ? Path.of(file.file()).getFileName().toString() : part.getKey();
                out.writeBytes(("Content-Disposition: form-data; name=\"" + part.getKey() + "\"; filename=\"" + filename + "\"\r\n"
                        + "Content-Type: " + (file.contentType() == null ? "application/octet-stream" : file.contentType())
                        + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.writeBytes(content);
            } else {
                out.writeBytes(("Content-Disposition: form-data; name=\"" + part.getKey() + "\"\r\n\r\n"
                        + part.getValue()).getBytes(StandardCharsets.UTF_8));
            }
            out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.writeBytes(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }
}
