package io.renova.web.settings;

import io.renova.core.ai.AiProvider;
import io.renova.core.ai.AiProviderFactory;
import io.renova.core.ai.AiSettings;
import io.renova.core.ai.NoAiProvider;
import io.renova.core.config.Secret;
import io.renova.core.config.Settings;
import io.renova.core.config.UserConfig;
import io.renova.core.engine.PluginRegistry;
import io.renova.core.rag.RagSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The AI settings the server migrates with, from the server's environment variables, then the Renova
 * user config file of the account the server runs as (the same keys and file as the CLI). Keys are the
 * customer's own; they are never returned, only masked.
 */
@Service
public class AiSettingsService {

    static final String PROVIDER = "ai.provider";
    static final String MODEL = "ai.model";
    static final String EFFORT = "ai.effort";
    static final String RAG = "rag.enabled";
    static final String RAG_BUDGET = "rag.budget";
    private static final Set<String> EDITABLE = Set.of(PROVIDER, MODEL, EFFORT, RAG, RAG_BUDGET);

    private final PluginRegistry registry;
    private final UserConfig userConfig;
    private final Map<String, String> environment;

    /** @param configFile the Renova user config to use; empty for the default (as the CLI uses) */
    @Autowired
    public AiSettingsService(PluginRegistry registry, @Value("${renova.user-config:}") String configFile) {
        this(registry, configFile.isBlank() ? UserConfig.defaultLocation() : new UserConfig(Path.of(configFile)), System.getenv());
    }

    AiSettingsService(PluginRegistry registry, UserConfig userConfig, Map<String, String> environment) {
        this.registry = registry;
        this.userConfig = userConfig;
        this.environment = environment;
    }

    /** What the console shows: effective values, where each comes from, and masked keys. */
    public record View(String provider, String model, String effort, boolean rag, List<ProviderView> providers,
                       String configFile) {
    }

    public record ProviderView(String name, String displayName, String defaultModel, boolean keyConfigured, String key,
                               String keySource) {
    }

    public View view() throws IOException {
        Settings settings = settings();
        List<ProviderView> providers = new ArrayList<>();
        for (AiProviderFactory f : registry.aiProviders()) {
            Optional<Settings.Value> key = settings.find(f.name() + ".apiKey");
            providers.add(new ProviderView(f.name(), f.displayName(), f.defaultModel(), key.isPresent(),
                    key.map(k -> Secret.of(k.value()).masked()).orElse(null), key.map(Settings.Value::source).orElse(null)));
        }
        return new View(settings.get(PROVIDER).orElse(NoAiProvider.NAME), settings.get(MODEL).orElse(null),
                settings.get(EFFORT).orElse(null), rag(settings).enabled(), providers, userConfig.file().toString());
    }

    public void update(Map<String, String> values) throws IOException {
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (!EDITABLE.contains(e.getKey())) {
                throw new IllegalArgumentException("Not an editable setting: " + e.getKey());
            }
            if (e.getKey().equals(PROVIDER) && !e.getValue().equals(NoAiProvider.NAME) && registry.aiProvider(e.getValue()).isEmpty()) {
                throw new IllegalArgumentException("Unknown AI provider: " + e.getValue());
            }
            if (e.getValue() == null || e.getValue().isBlank()) {
                userConfig.unset(e.getKey());
            } else {
                userConfig.set(e.getKey(), e.getValue().strip());
            }
        }
    }

    public void setKey(String provider, String key) throws IOException {
        factory(provider);
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("The key is empty");
        }
        userConfig.set(provider + ".apiKey", key.strip());
    }

    public boolean removeKey(String provider) throws IOException {
        factory(provider);
        return userConfig.unset(provider + ".apiKey");
    }

    /** Verifies the key and model without generating anything. */
    public String check() throws IOException {
        AiSettings ai = aiSettings();
        if (ai.equals(AiSettings.NONE)) {
            return "No AI provider selected; AI steps are reported as manual work.";
        }
        try (AiProvider provider = registry.ai(ai)) {
            return provider.check();
        }
    }

    public AiSettings aiSettings() throws IOException {
        Settings settings = settings();
        String provider = settings.get(PROVIDER).orElse(NoAiProvider.NAME);
        if (provider.equals(NoAiProvider.NAME)) {
            return AiSettings.NONE;
        }
        AiProviderFactory factory = factory(provider);
        Map<String, String> options = new LinkedHashMap<>();
        settings.get(EFFORT).ifPresent(v -> options.put("effort", v));
        return new AiSettings(provider, settings.get(MODEL).orElse(factory.defaultModel()),
                Secret.of(settings.get(provider + ".apiKey").orElse(null)), settings.get(provider + ".baseUrl").orElse(null),
                options);
    }

    public RagSettings ragSettings() throws IOException {
        return rag(settings());
    }

    private static RagSettings rag(Settings settings) {
        boolean enabled = settings.get(RAG).map(Boolean::parseBoolean).orElse(true);
        double budget = settings.get(RAG_BUDGET).map(Double::parseDouble).orElse(RagSettings.DEFAULT_BUDGET);
        return new RagSettings(enabled, budget);
    }

    private AiProviderFactory factory(String provider) {
        return registry.aiProvider(provider).orElseThrow(() -> new IllegalArgumentException("Unknown AI provider: " + provider));
    }

    private Settings settings() throws IOException {
        Map<String, String> env = new LinkedHashMap<>();
        putIfSet(env, PROVIDER, environment.get("RENOVA_AI_PROVIDER"));
        putIfSet(env, MODEL, environment.get("RENOVA_AI_MODEL"));
        putIfSet(env, EFFORT, environment.get("RENOVA_AI_EFFORT"));
        putIfSet(env, RAG, environment.get("RENOVA_RAG"));
        putIfSet(env, RAG_BUDGET, environment.get("RENOVA_RAG_BUDGET"));
        for (AiProviderFactory f : registry.aiProviders()) {
            putIfSet(env, f.name() + ".apiKey", environment.get(f.apiKeyEnvironmentVariable()));
            if (f.baseUrlEnvironmentVariable() != null) {
                putIfSet(env, f.name() + ".baseUrl", environment.get(f.baseUrlEnvironmentVariable()));
            }
        }
        return new Settings(List.of(new Settings.Source("environment", env),
                new Settings.Source("user config " + userConfig.file(), userConfig.read())));
    }

    private static void putIfSet(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }
}
