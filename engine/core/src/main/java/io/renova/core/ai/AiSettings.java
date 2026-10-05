package io.renova.core.ai;

import io.renova.core.config.Secret;

import java.util.Map;

/**
 * Everything needed to create an AI provider for one user or organisation. The CLI builds it from
 * flags, environment and the user config file; a server builds it from the account's stored
 * settings. The engine never reads credentials itself.
 *
 * @param model   null for the provider's default
 * @param baseUrl null for the provider's public endpoint; set for proxies or gateways
 * @param options provider-specific switches such as {@code effort}
 */
public record AiSettings(String provider, String model, Secret apiKey, String baseUrl, Map<String, String> options) {

    public static final AiSettings NONE = new AiSettings(NoAiProvider.NAME, null, null, null, Map.of());

    public AiSettings {
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    public String option(String key, String fallback) {
        return options.getOrDefault(key, fallback);
    }
}
