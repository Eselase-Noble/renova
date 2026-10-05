package io.renova.core.ai;

/**
 * Creates {@link AiProvider}s from user-supplied settings. Discovered with ServiceLoader, so adding
 * a vendor is adding a jar.
 */
public interface AiProviderFactory {

    /** Selected with {@code ai.provider} / {@code --ai}, e.g. "anthropic". */
    String name();

    String displayName();

    String defaultModel();

    /** Conventional environment variable for this vendor's key, e.g. ANTHROPIC_API_KEY. */
    String apiKeyEnvironmentVariable();

    /** Conventional environment variable for a custom endpoint, or null. */
    default String baseUrlEnvironmentVariable() {
        return null;
    }

    /** @throws AiProviderException when the settings are incomplete, e.g. no API key */
    AiProvider create(AiSettings settings);
}
