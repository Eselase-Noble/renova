package io.renova.desktop.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * A finished migration as its migrated copy records it: the JSON and Markdown reports, the behaviour comparison,
 * the AI audit log and the progress log. Live and past migrations are shown from the same files.
 */
public final class MigrationResult {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path workspace;
    private final JsonNode report;
    private final JsonNode behaviour;

    /** One AI exchange from .renova/ai/NNN.md. */
    public record AiExchange(String name, String markdown) {
    }

    private MigrationResult(Path workspace, JsonNode report, JsonNode behaviour) {
        this.workspace = workspace;
        this.report = report;
        this.behaviour = behaviour;
    }

    /** Reads the reports of a migrated copy. */
    public static MigrationResult load(Path workspace) throws IOException {
        Path dir = workspace.resolve(".renova");
        Path report = dir.resolve("report.json");
        if (!Files.isRegularFile(report)) {
            throw new IOException("No Renova report in " + workspace + ": not a finished migration");
        }
        Path behaviour = dir.resolve("behaviour.json");
        return new MigrationResult(workspace, JSON.readTree(report.toFile()),
                Files.isRegularFile(behaviour) ? JSON.readTree(behaviour.toFile()) : null);
    }

    public Path workspace() {
        return workspace;
    }

    /** The whole JSON report (assessment, plan and the migration). */
    public JsonNode report() {
        return report;
    }

    public JsonNode migration() {
        return report.path("migration");
    }

    /** behaviour.json, when behaviour was compared; null otherwise. */
    public JsonNode behaviour() {
        return behaviour;
    }

    public String projectName() {
        String root = report.path("project").path("root").asText("");
        return root.isEmpty() ? workspace.getFileName().toString() : Path.of(root).getFileName().toString();
    }

    public boolean buildPasses() {
        JsonNode v = migration().path("verification");
        return v.isMissingNode() || v.isNull() || v.path("success").asBoolean();
    }

    /** PASSED when the build passes and behaviour, if compared, is the same; otherwise FAILED. */
    public String state() {
        String b = behaviour == null ? null : behaviour.path("status").asText();
        boolean behaviourOk = b == null || b.equals("SAME") || b.equals("SKIPPED");
        // A stage that could not run made none of its changes: a passing build then proves nothing.
        boolean stagesRan = true;
        for (JsonNode stage : migration().path("stages")) {
            stagesRan &= !stage.path("status").asText().equals("FAILED");
        }
        return buildPasses() && behaviourOk && stagesRan ? "PASSED" : "FAILED";
    }

    public Path reportMarkdown() {
        return workspace.resolve(".renova/report.md");
    }

    public String markdown() throws IOException {
        return Files.readString(reportMarkdown(), StandardCharsets.UTF_8);
    }

    public String behaviourMarkdown() throws IOException {
        Path file = workspace.resolve(".renova/behaviour.md");
        return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
    }

    public List<AiExchange> aiExchanges() throws IOException {
        Path dir = workspace.resolve(".renova/ai");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<AiExchange> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                result.add(new AiExchange(f.getFileName().toString().replace(".md", ""), Files.readString(f, StandardCharsets.UTF_8)));
            }
        }
        return result;
    }

    public List<String> progressLog() throws IOException {
        Path file = workspace.resolve(".renova/progress.log");
        return Files.isRegularFile(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
    }

    /** Saves a run's progress lines with its reports. */
    public static void saveProgress(Path workspace, List<String> lines) {
        try {
            Path dir = Files.createDirectories(workspace.resolve(".renova"));
            Files.write(dir.resolve("progress.log"), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // The log is a convenience.
        }
    }
}
