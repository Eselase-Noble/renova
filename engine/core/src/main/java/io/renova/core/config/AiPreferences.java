package io.renova.core.config;

import io.renova.core.ai.AiProvider;
import io.renova.core.ai.AiProviderFactory;
import io.renova.core.ai.AiSettings;
import io.renova.core.ai.NoAiProvider;
import io.renova.core.engine.PluginRegistry;
import io.renova.core.rag.RagSettings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AI settings for one user (desktop app, IDE plugins), from the same places and keys as the CLI: environment variables first, then the
 * Renova user config file ({@code ~/.config/renova/config.properties}), where this app saves them. The key is
 * the user's own and only ever shown masked.
 */
public final class AiPreferences {

    public static final String PROVIDER = "ai.provider";
    public static final String MODEL = "ai.model";
    public static final String EFFORT = "ai.effort";
    public static final String RAG = "rag.enabled";

    private final PluginRegistry registry;
    private final UserConfig config;
    private final Map<String, String> environment;

    public AiPreferences(PluginRegistry registry) {
        this(registry, UserConfig.defaultLocation(), System.getenv());
    }

    public AiPreferences(PluginRegistry registry, UserConfig config, Map<String, String> environment) {
        this.registry = registry;
        this.config = config;
        this.environment = environment;
    }

    /** @param baseUrl a custom endpoint (for example an on-premises OpenAI-compatible server), or null */
    public record Provider(String name, String displayName, String defaultModel, boolean keyConfigured, String maskedKey,
                           String keySource, String baseUrl) {
    }

    public record View(String provider, String model, String effort, boolean rag, List<Provider> providers, String configFile) {
    }

    public View view() throws IOException {
        Settings s = settings();
        List<Provider> providers = new ArrayList<>();
        for (AiProviderFactory f : registry.aiProviders()) {
            Optional<Settings.Value> key = s.find(f.name() + ".apiKey");
            providers.add(new Provider(f.name(), f.displayName(), f.defaultModel(), key.isPresent(),
                    key.map(k -> Secret.of(k.value()).masked()).orElse(null), key.map(Settings.Value::source).orElse(null),
                    s.get(f.name() + ".baseUrl").orElse(null)));
        }
        return new View(s.get(PROVIDER).orElse(NoAiProvider.NAME), s.get(MODEL).orElse(null), s.get(EFFORT).orElse(null),
                s.get(RAG).map(Boolean::parseBoolean).orElse(true), providers, config.file().toString());
    }

    /** Saves to the user config; a blank value removes the setting. */
    public void save(String key, String value) throws IOException {
        if (value == null || value.isBlank()) {
            config.unset(key);
        } else {
            config.set(key, value.strip());
        }
    }

    /** A custom endpoint for the provider; blank removes it. */
    public void setBaseUrl(String provider, String url) throws IOException {
        if (registry.aiProvider(provider).isEmpty()) {
            throw new IllegalArgumentException("Unknown AI provider: " + provider);
        }
        if (url != null && !url.isBlank() && !url.strip().matches("https?://\\S+")) {
            throw new IllegalArgumentException("An endpoint must start with http:// or https://");
        }
        save(provider + ".baseUrl", url);
    }

    public void setKey(String provider, String key) throws IOException {
        if (registry.aiProvider(provider).isEmpty()) {
            throw new IllegalArgumentException("Unknown AI provider: " + provider);
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("The key is empty");
        }
        config.set(provider + ".apiKey", key.strip());
    }

    public void removeKey(String provider) throws IOException {
        config.unset(provider + ".apiKey");
    }

    public AiSettings aiSettings() throws IOException {
        Settings s = settings();
        String provider = s.get(PROVIDER).orElse(NoAiProvider.NAME);
        if (provider.equals(NoAiProvider.NAME)) {
            return AiSettings.NONE;
        }
        AiProviderFactory factory = registry.aiProvider(provider)
                .orElseThrow(() -> new IllegalArgumentException("Unknown AI provider: " + provider));
        Map<String, String> options = new LinkedHashMap<>();
        s.get(EFFORT).ifPresent(v -> options.put("effort", v));
        return new AiSettings(provider, s.get(MODEL).orElse(factory.defaultModel()),
                Secret.of(s.get(provider + ".apiKey").orElse(null)), s.get(provider + ".baseUrl").orElse(null), options);
    }

    public RagSettings ragSettings() throws IOException {
        return new RagSettings(settings().get(RAG).map(Boolean::parseBoolean).orElse(true), RagSettings.DEFAULT_BUDGET);
    }

    /** Verifies the key and model without generating anything. */
    public String check() throws IOException {
        AiSettings ai = aiSettings();
        if (ai.equals(AiSettings.NONE)) {
            return "No AI provider selected; AI steps are listed as manual work.";
        }
        try (AiProvider provider = registry.ai(ai)) {
            return provider.check();
        }
    }

    private Settings settings() throws IOException {
        Map<String, String> env = new LinkedHashMap<>();
        put(env, PROVIDER, environment.get("RENOVA_AI_PROVIDER"));
        put(env, MODEL, environment.get("RENOVA_AI_MODEL"));
        put(env, EFFORT, environment.get("RENOVA_AI_EFFORT"));
        put(env, RAG, environment.get("RENOVA_RAG"));
        for (AiProviderFactory f : registry.aiProviders()) {
            put(env, f.name() + ".apiKey", environment.get(f.apiKeyEnvironmentVariable()));
            if (f.baseUrlEnvironmentVariable() != null) {
                put(env, f.name() + ".baseUrl", environment.get(f.baseUrlEnvironmentVariable()));
            }
        }
        return new Settings(List.of(new Settings.Source("environment", env),
                new Settings.Source("Renova settings", config.read())));
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }
}
