package io.renova.ai.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.renova.core.ai.AiProvider;
import io.renova.core.ai.AiProviderException;
import io.renova.core.ai.AiSettings;
import io.renova.core.ai.FixRequest;
import io.renova.core.ai.Proposal;
import io.renova.core.ai.RequestFile;
import io.renova.core.config.Secret;
import io.renova.core.engine.BuildError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs the real OpenAI SDK against a local stand-in for Chat Completions; no network or key needed. */
class OpenAiProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authHeader = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String answer;
    private volatile String finishReason = "stop";
    private volatile String refusal;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if (status != 200) {
                byte[] body = "{\"error\":{\"message\":\"Incorrect API key provided\",\"type\":\"invalid_request_error\"}}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("content-type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("content-type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(sse().getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private AiProvider provider(Map<String, String> options) {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        return new OpenAiProviderFactory().create(new AiSettings("openai", null, Secret.of("sk-proj-test-5678"), url, options));
    }

    private static FixRequest request() {
        return new FixRequest("Java 8 → 21", List.of(
                new RequestFile("src/A.java", "import javax.annotation.Resource;\nclass A {}\n", RequestFile.Role.TARGET, null),
                new RequestFile("pom.xml", "<project/>\n", RequestFile.Role.RELATED, "build file of module a")),
                List.of(), List.of(new BuildError("src/A.java", 1, "package jakarta.annotation does not exist")));
    }

    @Test
    void returnsMultiFileEditsAndSendsTheUsersOwnKey() throws Exception {
        answer = JSON.writeValueAsString(Map.of("rationale", "Declare jakarta.annotation-api.", "edits",
                List.of(Map.of("path", "pom.xml", "content", "<project><!-- dep --></project>\n"))));
        try (AiProvider ai = provider(Map.of("effort", "high"))) {
            Proposal p = ai.propose(request());
            assertThat(p.outcome()).isEqualTo(Proposal.Outcome.CHANGED);
            assertThat(p.edits()).containsOnlyKeys("pom.xml");
            assertThat(p.inputTokens()).isEqualTo(120);
            assertThat(p.outputTokens()).isEqualTo(45);
        }
        assertThat(authHeader.get()).isEqualTo("Bearer sk-proj-test-5678");
        JsonNode body = JSON.readTree(requestBody.get());
        assertThat(body.path("model").asText()).isEqualTo("gpt-5.5");
        assertThat(body.path("stream").asBoolean()).isTrue();
        assertThat(body.path("stream_options").path("include_usage").asBoolean()).isTrue();
        assertThat(body.path("reasoning_effort").asText()).isEqualTo("high");
        assertThat(body.path("response_format").path("json_schema").path("strict").asBoolean()).isTrue();
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(body.path("messages").get(1).path("content").asText())
                .contains("role=\"target\"", "role=\"related\"", "<build_errors>");
    }

    @Test
    void omitsReasoningEffortUnlessConfigured() throws Exception {
        answer = JSON.writeValueAsString(Map.of("rationale", "Nothing to do.", "edits", List.of()));
        try (AiProvider ai = provider(Map.of())) {
            assertThat(ai.propose(request()).outcome()).isEqualTo(Proposal.Outcome.UNCHANGED);
        }
        assertThat(JSON.readTree(requestBody.get()).has("reasoning_effort")).isFalse();
    }

    @Test
    void declinesRefusalsTruncationAndFilteredResponses() throws Exception {
        answer = "{\"rationale\": \"x\", \"edits\": [";
        finishReason = "length";
        try (AiProvider ai = provider(Map.of())) {
            assertThat(ai.propose(request()).rationale()).contains("output limit");
        }
        finishReason = "content_filter";
        try (AiProvider ai = provider(Map.of())) {
            assertThat(ai.propose(request()).rationale()).contains("content filter");
        }
        finishReason = "stop";
        answer = null;
        refusal = "I can't help with that.";
        try (AiProvider ai = provider(Map.of())) {
            assertThat(ai.propose(request())).extracting(Proposal::outcome).isEqualTo(Proposal.Outcome.DECLINED);
        }
    }

    @Test
    void rejectedKeyIsFatalAndNeverEchoed() {
        status = 401;
        try (AiProvider ai = provider(Map.of())) {
            assertThatThrownBy(() -> ai.propose(request()))
                    .isInstanceOf(AiProviderException.class)
                    .hasMessageContaining("rejected the API key")
                    .hasMessageNotContaining("sk-proj-test-5678")
                    .matches(e -> ((AiProviderException) e).fatal());
        }
    }

    @Test
    void missingKeyFailsBeforeAnyRequest() {
        assertThatThrownBy(() -> new OpenAiProviderFactory().create(new AiSettings("openai", null, null, null, Map.of())))
                .hasMessageContaining("OPENAI_API_KEY");
        assertThat(requestBody.get()).isNull();
    }

    private String sse() throws IOException {
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("role", "assistant");
        if (answer != null) {
            delta.put("content", answer);
        }
        if (refusal != null) {
            delta.put("refusal", refusal);
        }
        return chunk(List.of(choice(delta, null)), null)
                + chunk(List.of(choice(Map.of(), finishReason)), null)
                + chunk(List.of(), Map.of("prompt_tokens", 120, "completion_tokens", 45, "total_tokens", 165))
                + "data: [DONE]\n\n";
    }

    private static Map<String, Object> choice(Map<String, Object> delta, String finish) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("index", 0);
        c.put("delta", delta);
        c.put("finish_reason", finish);
        return c;
    }

    private static String chunk(List<Map<String, Object>> choices, Map<String, Object> usage) throws IOException {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", "chatcmpl-1");
        c.put("object", "chat.completion.chunk");
        c.put("created", 1);
        c.put("model", "gpt-5.5");
        c.put("choices", choices);
        if (usage != null) {
            c.put("usage", usage);
        }
        return "data: " + JSON.writeValueAsString(c) + "\n\n";
    }
}
