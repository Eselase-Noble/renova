package io.renova.ai.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.renova.core.ai.AiProvider;
import io.renova.core.ai.AiProviderException;
import io.renova.core.ai.AiSettings;
import io.renova.core.ai.FixRequest;
import io.renova.core.ai.Proposal;
import io.renova.core.config.Secret;
import io.renova.core.engine.BuildError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs the real SDK against a local stand-in for the Messages API; no network or key needed. */
class AnthropicProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> apiKeyHeader = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String answerJson;
    private volatile String stopReason = "end_turn";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            if (status != 200) {
                byte[] body = "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}"
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
                out.write(sse(answerJson, stopReason).getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private AiProvider provider(Map<String, String> options) {
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        return new AnthropicProviderFactory().create(new AiSettings("anthropic", null,
                Secret.of("sk-ant-test-key-1234"), url, options));
    }

    private static FixRequest request() {
        return new FixRequest("Java 8 → 21", "src/A.java", "import sun.misc.BASE64Decoder;\nclass A {}\n",
                List.of("Replace sun.misc.BASE64Decoder: use java.util.Base64"),
                List.of(new BuildError("src/A.java", 1, "package sun.misc does not exist")));
    }

    @Test
    void returnsTheEditedFileAndSendsTheUsersOwnKey() throws Exception {
        answerJson = JSON.writeValueAsString(Map.of("changed", true,
                "content", "import java.util.Base64;\nclass A {}\n", "rationale", "Use java.util.Base64."));
        try (AiProvider ai = provider(Map.of("effort", "medium"))) {
            Proposal p = ai.propose(request());
            assertThat(p.outcome()).isEqualTo(Proposal.Outcome.CHANGED);
            assertThat(p.newContent()).contains("java.util.Base64");
            assertThat(p.inputTokens()).isEqualTo(120);
            assertThat(p.outputTokens()).isEqualTo(45);
        }
        assertThat(apiKeyHeader.get()).isEqualTo("sk-ant-test-key-1234");
        JsonNode body = JSON.readTree(requestBody.get());
        assertThat(body.path("model").asText()).isEqualTo("claude-opus-5-5");
        assertThat(body.path("stream").asBoolean()).isTrue();
        assertThat(body.path("output_config").path("effort").asText()).isEqualTo("medium");
        assertThat(body.path("output_config").path("format").path("schema").path("required"))
                .extracting(JsonNode::asText).containsExactly("changed", "content", "rationale");
        assertThat(body.has("thinking")).as("adaptive thinking is the model default").isFalse();
        assertThat(body.has("fallbacks")).as("no fallbacks through a custom base URL").isFalse();
        String userText = body.path("messages").get(0).path("content").asText();
        assertThat(userText).contains("<file path=\"src/A.java\">", "<build_errors>", "line 1: package sun.misc");
    }

    @Test
    void reportsNoChange() throws Exception {
        answerJson = JSON.writeValueAsString(Map.of("changed", false, "content", "", "rationale", "Already compatible."));
        try (AiProvider ai = provider(Map.of())) {
            assertThat(ai.propose(request())).extracting(Proposal::outcome, Proposal::rationale)
                    .containsExactly(Proposal.Outcome.UNCHANGED, "Already compatible.");
        }
    }

    @Test
    void declinesTruncatedAndRefusedResponsesInsteadOfWritingThem() throws Exception {
        answerJson = "{\"changed\": true, \"content\": \"class A {";
        stopReason = "max_tokens";
        try (AiProvider ai = provider(Map.of())) {
            assertThat(ai.propose(request()).outcome()).isEqualTo(Proposal.Outcome.DECLINED);
        }
        stopReason = "refusal";
        try (AiProvider ai = provider(Map.of())) {
            assertThat(ai.propose(request()).rationale()).startsWith("the model declined");
        }
    }

    @Test
    void rejectedKeyIsFatalAndNeverEchoed() {
        status = 401;
        try (AiProvider ai = provider(Map.of())) {
            assertThatThrownBy(() -> ai.propose(request()))
                    .isInstanceOf(AiProviderException.class)
                    .hasMessageContaining("rejected the API key")
                    .hasMessageNotContaining("sk-ant-test-key-1234")
                    .matches(e -> ((AiProviderException) e).fatal());
        }
    }

    @Test
    void missingKeyAndBadEffortFailBeforeAnyRequest() {
        AnthropicProviderFactory factory = new AnthropicProviderFactory();
        assertThatThrownBy(() -> factory.create(new AiSettings("anthropic", null, null, null, Map.of())))
                .hasMessageContaining("ANTHROPIC_API_KEY");
        assertThatThrownBy(() -> provider(Map.of("effort", "extreme"))).hasMessageContaining("Unknown effort");
        assertThat(requestBody.get()).isNull();
    }

    @Test
    void declinesFilesTooLargeForOneResponse() throws Exception {
        String huge = "x".repeat(AnthropicProvider.MAX_FILE_CHARS + 1);
        try (AiProvider ai = provider(Map.of())) {
            Proposal p = ai.propose(new FixRequest("goal", "Big.java", huge, List.of("rule"), List.of()));
            assertThat(p.outcome()).isEqualTo(Proposal.Outcome.DECLINED);
        }
        assertThat(requestBody.get()).isNull();
    }

    private static String sse(String text, String stopReason) throws IOException {
        String delta = JSON.writeValueAsString(Map.of("type", "content_block_delta", "index", 0,
                "delta", Map.of("type", "text_delta", "text", text)));
        return event("message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\","
                + "\"role\":\"assistant\",\"model\":\"claude-opus-5-5\",\"content\":[],\"stop_reason\":null,"
                + "\"stop_sequence\":null,\"usage\":{\"input_tokens\":120,\"output_tokens\":1}}}")
                + event("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")
                + event("content_block_delta", delta)
                + event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
                + event("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"" + stopReason
                + "\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":45}}")
                + event("message_stop", "{\"type\":\"message_stop\"}");
    }

    private static String event(String name, String data) {
        return "event: " + name + "\ndata: " + data + "\n\n";
    }
}
