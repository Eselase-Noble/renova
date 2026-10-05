package io.renova.core.behaviour;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioFileTest {

    @Test
    void encodesFormsJsonAndUploadsAndKeepsCaptures(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("stock.csv"), "sku,qty\nA-1,4\n");
        Path file = dir.resolve(ScenarioFile.DEFAULT_NAME);
        Files.writeString(file, """
                database:
                  init: schema.sql
                  ignoreColumns: [filed_at]
                  app: { claims.db.url: "jdbc:postgresql://${host}:5432/app" }
                scenarios:
                  - id: file-a-claim
                    description: file and read back
                    steps:
                      - { method: POST, path: /claims, form: { holder: Ama Mensah, ref: "${token}" },
                          capture: { claim: 'header:Location:/claims/(\\d+)' } }
                      - { path: "/claims/${claim}", ignore: ["filed at [^<]*"] }
                  - id: api
                    steps:
                      - { method: PUT, path: /api/claims/1, json: { status: APPROVED, amount: 12.5 } }
                  - id: upload
                    steps:
                      - { path: /items/import, multipart: { note: weekly, file: { file: stock.csv, contentType: text/csv } } }
                """);
        ScenarioFile parsed = ScenarioFile.load(file);
        assertThat(parsed.database().image()).isEqualTo("postgres:16-alpine");
        assertThat(parsed.database().app()).containsEntry("claims.db.url", "jdbc:postgresql://${host}:5432/app");

        List<Scenario> scenarios = parsed.toScenarios(dir);
        assertThat(scenarios).extracting(Scenario::id).containsExactly("file.file-a-claim", "file.api", "file.upload");
        assertThat(scenarios).extracting(Scenario::mutating).containsExactly(true, true, true);

        Step post = scenarios.get(0).steps().get(0);
        assertThat(new String(post.body(), StandardCharsets.UTF_8)).isEqualTo("holder=Ama+Mensah&ref=${token}");
        assertThat(post.headers()).containsEntry("Content-Type", "application/x-www-form-urlencoded");
        assertThat(post.substituteBody()).isTrue();
        assertThat(post.captures()).containsEntry("claim", "header:Location:/claims/(\\d+)");
        assertThat(scenarios.get(0).steps().get(1)).satisfies(s -> {
            assertThat(s.method()).isEqualTo("GET");
            assertThat(s.ignore()).containsExactly("filed at [^<]*");
        });

        assertThat(new String(scenarios.get(1).steps().get(0).body(), StandardCharsets.UTF_8))
                .isEqualTo("{\"status\":\"APPROVED\",\"amount\":12.5}");

        Step upload = scenarios.get(2).steps().get(0);
        assertThat(upload.method()).isEqualTo("POST");
        assertThat(upload.substituteBody()).isFalse();
        assertThat(new String(upload.body(), StandardCharsets.UTF_8))
                .contains("Content-Disposition: form-data; name=\"note\"\r\n\r\nweekly\r\n")
                .contains("name=\"file\"; filename=\"stock.csv\"\r\nContent-Type: text/csv\r\n\r\nsku,qty\nA-1,4\n\r\n")
                .endsWith("--" + ScenarioFile.BOUNDARY + "--\r\n");
    }

    @Test
    void rejectsAmbiguousSteps(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(ScenarioFile.DEFAULT_NAME);
        Files.writeString(file, """
                scenarios:
                  - id: both
                    steps: [ { method: POST, path: /x, form: { a: b }, body: "c" } ]
                """);
        assertThatThrownBy(() -> ScenarioFile.load(file).toScenarios(dir)).hasMessageContaining("only one of form, json, body or multipart");
    }

    @Test
    void writesTheProbeInputAndRepeatsOnlyReadOnlyScenarios() {
        Scenario read = new Scenario("s1", "GET", "/items", "discovered");
        Scenario write = new Scenario("file.post", "file", List.of(
                new Step("POST", "/claims", Map.of("Content-Type", "application/x-www-form-urlencoded"),
                        "holder=Ama".getBytes(StandardCharsets.UTF_8), true, Map.of("claim", "body:(\\d+)"), null)));
        assertThat(DockerSandbox.encode(List.of(read, write))).isEqualTo("""
                SCENARIO s1 full
                STEP GET /items
                END
                SCENARIO file.post single
                STEP POST /claims
                H Content-Type: application/x-www-form-urlencoded
                B aG9sZGVyPUFtYQ==
                S
                C claim Ym9keTooXGQrKQ==
                END
                """);
    }

    @Test
    void comparesWhatEachApplicationChangedInItsDatabase() {
        Map<String, List<String>> empty = Map.of("claims", List.of());
        Map<String, List<String>> baselineAfter = Map.of("claims", List.of(
                "{\"id\":1,\"holder\":\"Ama Mensah\",\"amount\":1520.76,\"status\":\"NEW\",\"filed_at\":\"2024-05-01T10:15:00\"}"));
        Map<String, List<String>> sameRow = Map.of("claims", List.of(
                "{\"id\":1,\"holder\":\"Ama Mensah\",\"amount\":1520.76,\"status\":\"NEW\",\"filed_at\":\"2026-10-05T18:00:00\"}"));
        // Differs only in whitespace, which matters in stored data.
        Map<String, List<String>> untrimmed = Map.of("claims", List.of(
                "{\"id\":1,\"holder\":\"  Ama Mensah \",\"amount\":1520.76,\"status\":\"NEW\",\"filed_at\":\"2026-10-05T18:00:00\"}"));

        assertThat(DatabaseSnapshot.compare(empty, baselineAfter, empty, sameRow, List.of("filed_at"))).isEmpty();
        assertThat(DatabaseSnapshot.compare(empty, baselineAfter, empty, untrimmed, List.of("filed_at"))).singleElement()
                .asString().startsWith("table claims: the original added 1 row(s): {\"amount\":\"1520.76\",\"holder\":\"Ama Mensah\"")
                .contains("the migrated app added 1 row(s): {\"amount\":\"1520.76\",\"holder\":\"  Ama Mensah \"");
        assertThat(DatabaseSnapshot.compare(baselineAfter, empty, baselineAfter, baselineAfter, List.of())).singleElement()
                .asString().contains("the original removed 1 row(s)").contains("the migrated app removed no rows");
    }
}
