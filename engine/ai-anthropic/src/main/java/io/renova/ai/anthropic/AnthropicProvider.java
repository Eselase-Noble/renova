package io.renova.ai.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.NotFoundException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.helpers.BetaMessageAccumulator;
import com.anthropic.models.beta.messages.BetaJsonOutputFormat;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.anthropic.models.models.ModelInfo;
import io.renova.core.ai.AiProvider;
import io.renova.core.ai.AiProviderException;
import io.renova.core.ai.AiSettings;
import io.renova.core.ai.EditPrompt;
import io.renova.core.ai.FixRequest;
import io.renova.core.ai.Proposal;

import java.time.Duration;
import java.util.Set;

/**
 * Proposes whole-file edits (one or more files per request) with Claude. Responses are constrained to a JSON schema, streamed (files
 * can be long), and checked for refusals and truncation before anything is written.
 */
final class AnthropicProvider implements AiProvider {

    /** Models that accept server-side refusal fallbacks on the Claude API. */
    private static final Set<String> FALLBACK_MODELS = Set.of("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5",
            "claude-sonnet-5-5");
    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";
    private static final long MAX_OUTPUT_TOKENS = 64_000;
    /** Larger requests would not fit in one response; they are declined rather than truncated. */
    static final int MAX_REQUEST_CHARS = 150_000;

    private final AnthropicClient client;
    private final String model;
    private final BetaOutputConfig.Effort effort;
    private final boolean fallbacks;

    AnthropicProvider(AiSettings settings) {
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .apiKey(settings.apiKey().reveal())
                .maxRetries(3)
                .timeout(Duration.ofMinutes(15));
        if (settings.baseUrl() != null) {
            builder.baseUrl(settings.baseUrl());
        }
        this.client = builder.build();
        this.model = settings.model() == null ? AnthropicProviderFactory.DEFAULT_MODEL : settings.model();
        this.effort = effort(settings.option("effort", "high"));
        // Fallbacks are a Claude API feature; gateways behind a custom base URL may not accept them.
        this.fallbacks = !"off".equals(settings.option("fallbacks", "default"))
                && settings.baseUrl() == null && FALLBACK_MODELS.contains(model);
    }

    @Override
    public String name() {
        return AnthropicProviderFactory.NAME;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public String check() {
        try {
            ModelInfo info = client.models().retrieve(model);
            return "Anthropic API key accepted; model " + info.id() + " (" + info.displayName() + ") is available";
        } catch (RuntimeException e) {
            throw translate(e);
        }
    }

    @Override
    public Proposal propose(FixRequest request) {
        if (request.totalChars() > MAX_REQUEST_CHARS) {
            return Proposal.declined("files too large for a whole-file edit (" + request.totalChars()
                    + " characters, limit " + MAX_REQUEST_CHARS + "); edit manually or split the file first", 0, 0);
        }
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_OUTPUT_TOKENS)
                .system(EditPrompt.SYSTEM)
                .outputConfig(BetaOutputConfig.builder()
                        .effort(effort)
                        .format(BetaJsonOutputFormat.builder().schema(schema()).build())
                        .build())
                .addUserMessage(EditPrompt.userMessage(request));
        if (fallbacks) {
            params.addBeta(FALLBACK_BETA).putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }

        BetaMessage message;
        try {
            BetaMessageAccumulator accumulator = BetaMessageAccumulator.create();
            try (StreamResponse<BetaRawMessageStreamEvent> stream = client.beta().messages().createStreaming(params.build())) {
                stream.stream().forEach(accumulator::accumulate);
            }
            message = accumulator.message();
        } catch (RuntimeException e) {
            throw translate(e);
        }
        long in = message.usage().inputTokens();
        long out = message.usage().outputTokens();

        BetaStopReason stop = message.stopReason().orElse(null);
        if (BetaStopReason.REFUSAL.equals(stop)) {
            String detail = message.stopDetails().flatMap(d -> d.explanation()).orElse("no explanation given");
            return Proposal.declined("the model declined this request: " + detail, in, out);
        }
        if (BetaStopReason.MAX_TOKENS.equals(stop)) {
            return Proposal.declined("the response reached the output limit before the file was complete", in, out);
        }
        if (BetaStopReason.MODEL_CONTEXT_WINDOW_EXCEEDED.equals(stop)) {
            return Proposal.declined("the file and context exceed the model's context window", in, out);
        }

        StringBuilder text = new StringBuilder();
        message.content().forEach(block -> block.text().ifPresent(t -> text.append(t.text())));
        return EditPrompt.parse(text.toString(), in, out);
    }

    @Override
    public void close() {
        client.close();
    }

    private static BetaJsonOutputFormat.Schema schema() {
        BetaJsonOutputFormat.Schema.Builder schema = BetaJsonOutputFormat.Schema.builder();
        EditPrompt.responseSchema().forEach((k, v) -> schema.putAdditionalProperty(k, JsonValue.from(v)));
        return schema.build();
    }

    private static BetaOutputConfig.Effort effort(String value) {
        return switch (value) {
            case "low" -> BetaOutputConfig.Effort.LOW;
            case "medium" -> BetaOutputConfig.Effort.MEDIUM;
            case "high" -> BetaOutputConfig.Effort.HIGH;
            case "xhigh" -> BetaOutputConfig.Effort.XHIGH;
            case "max" -> BetaOutputConfig.Effort.MAX;
            default -> throw new AiProviderException("Unknown effort '" + value + "'; use low, medium, high, xhigh or max", true);
        };
    }

    /** Maps SDK errors to fatal (configuration) and per-file failures, without exposing credentials. */
    private AiProviderException translate(RuntimeException e) {
        if (e instanceof UnauthorizedException || e instanceof PermissionDeniedException) {
            return new AiProviderException("Anthropic rejected the API key (HTTP "
                    + ((AnthropicServiceException) e).statusCode() + "). Check the key you configured.", true, e);
        }
        if (e instanceof NotFoundException) {
            return new AiProviderException("Model '" + model + "' was not found for this API key", true, e);
        }
        if (e instanceof RateLimitException) {
            return new AiProviderException("rate limited by the Anthropic API after retries", false, e);
        }
        if (e instanceof AnthropicServiceException s) {
            return new AiProviderException("Anthropic API error (HTTP " + s.statusCode() + "): " + e.getMessage(), false, e);
        }
        if (e instanceof AnthropicIoException) {
            return new AiProviderException("could not reach the Anthropic API: " + e.getMessage(), false, e);
        }
        if (e instanceof AiProviderException a) {
            return a;
        }
        return new AiProviderException("unexpected error from the Anthropic client: " + e, false, e);
    }
}
