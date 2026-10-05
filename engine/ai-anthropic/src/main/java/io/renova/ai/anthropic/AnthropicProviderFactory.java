package io.renova.ai.anthropic;

import io.renova.core.ai.AiProvider;
import io.renova.core.ai.AiProviderException;
import io.renova.core.ai.AiProviderFactory;
import io.renova.core.ai.AiSettings;

public final class AnthropicProviderFactory implements AiProviderFactory {

    public static final String NAME = "anthropic";
    public static final String DEFAULT_MODEL = "claude-opus-5-5";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String displayName() {
        return "Anthropic Claude";
    }

    @Override
    public String defaultModel() {
        return DEFAULT_MODEL;
    }

    @Override
    public String apiKeyEnvironmentVariable() {
        return "ANTHROPIC_API_KEY";
    }

    @Override
    public String baseUrlEnvironmentVariable() {
        return "ANTHROPIC_BASE_URL";
    }

    @Override
    public AiProvider create(AiSettings settings) {
        if (settings.apiKey() == null) {
            throw new AiProviderException("No Anthropic API key configured. Set ANTHROPIC_API_KEY, use --env-file, "
                    + "or run: renova config set-key anthropic", true);
        }
        return new AnthropicProvider(settings);
    }
}
