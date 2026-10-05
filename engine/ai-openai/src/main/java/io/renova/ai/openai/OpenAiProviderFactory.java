package io.renova.ai.openai;

import io.renova.core.ai.AiProvider;
import io.renova.core.ai.AiProviderException;
import io.renova.core.ai.AiProviderFactory;
import io.renova.core.ai.AiSettings;

/**
 * OpenAI, and any server that implements the OpenAI Chat Completions API (Azure OpenAI, vLLM,
 * Ollama, LM Studio) when {@code openai.baseUrl} points at it. The latter is how customers keep code
 * on their own network.
 */
public final class OpenAiProviderFactory implements AiProviderFactory {

    public static final String NAME = "openai";
    public static final String DEFAULT_MODEL = "gpt-5.5";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String displayName() {
        return "OpenAI (or an OpenAI-compatible endpoint)";
    }

    @Override
    public String defaultModel() {
        return DEFAULT_MODEL;
    }

    @Override
    public String apiKeyEnvironmentVariable() {
        return "OPENAI_API_KEY";
    }

    @Override
    public String baseUrlEnvironmentVariable() {
        return "OPENAI_BASE_URL";
    }

    @Override
    public AiProvider create(AiSettings settings) {
        if (settings.apiKey() == null) {
            throw new AiProviderException("No OpenAI API key configured. Set OPENAI_API_KEY, use --env-file, "
                    + "or run: renova config set-key openai (local servers that need no key accept any value)", true);
        }
        return new OpenAiProvider(settings);
    }
}
