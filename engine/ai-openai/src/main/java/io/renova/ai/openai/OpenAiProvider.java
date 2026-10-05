package io.renova.ai.openai;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.errors.NotFoundException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.openai.helpers.ChatCompletionAccumulator;
import com.openai.models.ReasoningEffort;
import com.openai.models.ResponseFormatJsonSchema;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.completions.CompletionUsage;
import io.renova.core.ai.AiProvider;
import io.renova.core.ai.AiProviderException;
import io.renova.core.ai.AiSettings;
import io.renova.core.ai.EditPrompt;
import io.renova.core.ai.FixRequest;
import io.renova.core.ai.Proposal;

import java.time.Duration;

/**
 * Proposes whole-file edits with OpenAI Chat Completions: strict JSON-schema responses, streamed,
 * with refusals, content filtering and truncation declined rather than written.
 */
final class OpenAiProvider implements AiProvider {

    static final int MAX_REQUEST_CHARS = 150_000;
    private static final long DEFAULT_MAX_OUTPUT_TOKENS = 64_000;

    private final OpenAIClient client;
    private final String model;
    private final ReasoningEffort effort;
    private final long maxOutputTokens;

    OpenAiProvider(AiSettings settings) {
        OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
                .apiKey(settings.apiKey().reveal())
                .maxRetries(3)
                .timeout(Duration.ofMinutes(15));
        if (settings.baseUrl() != null) {
            builder.baseUrl(settings.baseUrl());
        }
        this.client = builder.build();
        this.model = settings.model() == null ? OpenAiProviderFactory.DEFAULT_MODEL : settings.model();
        // Only sent when configured: models without reasoning, and many local servers, reject it.
        String effortSetting = settings.options().get("effort");
        this.effort = effortSetting == null ? null : effort(effortSetting);
        this.maxOutputTokens = Long.parseLong(settings.option("maxOutputTokens", String.valueOf(DEFAULT_MAX_OUTPUT_TOKENS)));
    }

    @Override
    public String name() {
        return OpenAiProviderFactory.NAME;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public String check() {
        try {
            return "OpenAI API key accepted; model " + client.models().retrieve(model).id() + " is available";
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
        ResponseFormatJsonSchema.JsonSchema.Schema.Builder schema = ResponseFormatJsonSchema.JsonSchema.Schema.builder();
        EditPrompt.responseSchema().forEach((k, v) -> schema.putAdditionalProperty(k, JsonValue.from(v)));
        ChatCompletionCreateParams.Builder params = ChatCompletionCreateParams.builder()
                .model(model)
                .maxCompletionTokens(maxOutputTokens)
                .addSystemMessage(EditPrompt.SYSTEM)
                .addUserMessage(EditPrompt.userMessage(request))
                .responseFormat(ResponseFormatJsonSchema.builder()
                        .jsonSchema(ResponseFormatJsonSchema.JsonSchema.builder()
                                .name("renova_edits")
                                .strict(true)
                                .schema(schema.build())
                                .build())
                        .build())
                .streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build());
        if (effort != null) {
            params.reasoningEffort(effort);
        }

        ChatCompletion completion;
        try {
            ChatCompletionAccumulator accumulator = ChatCompletionAccumulator.create();
            try (StreamResponse<ChatCompletionChunk> stream = client.chat().completions().createStreaming(params.build())) {
                stream.stream().forEach(accumulator::accumulate);
            }
            completion = accumulator.chatCompletion();
        } catch (RuntimeException e) {
            throw translate(e);
        }
        long in = completion.usage().map(CompletionUsage::promptTokens).orElse(0L);
        long out = completion.usage().map(CompletionUsage::completionTokens).orElse(0L);
        if (completion.choices().isEmpty()) {
            return Proposal.declined("the response contained no answer", in, out);
        }
        ChatCompletion.Choice choice = completion.choices().getFirst();
        ChatCompletionMessage message = choice.message();
        if (message.refusal().isPresent()) {
            return Proposal.declined("the model declined this request: " + message.refusal().get(), in, out);
        }
        if (ChatCompletion.Choice.FinishReason.LENGTH.equals(choice.finishReason())) {
            return Proposal.declined("the response reached the output limit before the files were complete", in, out);
        }
        if (ChatCompletion.Choice.FinishReason.CONTENT_FILTER.equals(choice.finishReason())) {
            return Proposal.declined("the response was stopped by the provider's content filter", in, out);
        }
        return EditPrompt.parse(message.content().orElse(""), in, out);
    }

    @Override
    public void close() {
        client.close();
    }

    private static ReasoningEffort effort(String value) {
        return switch (value) {
            case "none" -> ReasoningEffort.NONE;
            case "minimal" -> ReasoningEffort.MINIMAL;
            case "low" -> ReasoningEffort.LOW;
            case "medium" -> ReasoningEffort.MEDIUM;
            case "high" -> ReasoningEffort.HIGH;
            case "xhigh" -> ReasoningEffort.XHIGH;
            case "max" -> ReasoningEffort.MAX;
            default -> throw new AiProviderException("Unknown effort '" + value
                    + "'; use none, minimal, low, medium, high, xhigh or max", true);
        };
    }

    /** Maps SDK errors to fatal (configuration) and per-request failures, without exposing credentials. */
    private AiProviderException translate(RuntimeException e) {
        if (e instanceof UnauthorizedException || e instanceof PermissionDeniedException) {
            return new AiProviderException("OpenAI rejected the API key (HTTP "
                    + ((OpenAIServiceException) e).statusCode() + "). Check the key you configured.", true, e);
        }
        if (e instanceof NotFoundException) {
            return new AiProviderException("Model '" + model + "' was not found for this API key or endpoint", true, e);
        }
        if (e instanceof RateLimitException) {
            return new AiProviderException("rate limited by the OpenAI API after retries", false, e);
        }
        if (e instanceof OpenAIServiceException s) {
            return new AiProviderException("OpenAI API error (HTTP " + s.statusCode() + "): " + e.getMessage(), false, e);
        }
        if (e instanceof OpenAIIoException) {
            return new AiProviderException("could not reach the OpenAI API: " + e.getMessage(), false, e);
        }
        if (e instanceof AiProviderException a) {
            return a;
        }
        return new AiProviderException("unexpected error from the OpenAI client: " + e, false, e);
    }
}
