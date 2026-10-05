package io.renova.cli;

import io.renova.core.ai.AiProviderFactory;
import io.renova.core.ai.AiSettings;
import io.renova.core.ai.NoAiProvider;
import io.renova.core.config.DotEnv;
import io.renova.core.config.Secret;
import io.renova.core.config.Settings;
import io.renova.core.config.UserConfig;
import io.renova.core.engine.PluginRegistry;
import io.renova.core.rag.RagSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves AI settings for the CLI, highest priority first: command-line flags, environment
 * variables, the --env-file, then the user config file. Every source uses the same canonical keys
 * (ai.provider, anthropic.apiKey, ...); environment variables are mapped onto them.
 */
final class AiConfiguration {

    static final String PROVIDER = "ai.provider";
    static final String MODEL = "ai.model";
    static final String EFFORT = "ai.effort";
    static final String FALLBACKS = "ai.fallbacks";
    static final String RAG = "rag.enabled";
    static final String RAG_BUDGET = "rag.budget";

    private final PluginRegistry registry;
    private final Settings settings;
    private final UserConfig userConfig;

    private AiConfiguration(PluginRegistry registry, Settings settings, UserConfig userConfig) {
        this.registry = registry;
        this.settings = settings;
        this.userConfig = userConfig;
    }

    static AiConfiguration load(PluginRegistry registry, AiOptions options) throws IOException {
        UserConfig userConfig = UserConfig.defaultLocation();
        List<Settings.Source> sources = new ArrayList<>();

        Map<String, String> flags = new LinkedHashMap<>();
        if (options != null) {
            putIfSet(flags, PROVIDER, options.provider);
            putIfSet(flags, MODEL, options.model);
            putIfSet(flags, EFFORT, options.effort);
        }
        sources.add(new Settings.Source("command line", flags));
        sources.add(new Settings.Source("environment", fromEnvironment(registry, System.getenv())));
        if (options != null && options.envFile != null) {
            if (!Files.isRegularFile(options.envFile)) {
                throw new IllegalArgumentException("Env file not found: " + options.envFile);
            }
            sources.add(new Settings.Source("env file " + options.envFile,
                    fromEnvironment(registry, DotEnv.read(options.envFile))));
        }
        sources.add(new Settings.Source("user config " + userConfig.file(), userConfig.read()));
        return new AiConfiguration(registry, new Settings(sources), userConfig);
    }

    /** Maps RENOVA_AI_* and each provider's conventional variables (e.g. ANTHROPIC_API_KEY) to canonical keys. */
    static Map<String, String> fromEnvironment(PluginRegistry registry, Map<String, String> env) {
        Map<String, String> values = new LinkedHashMap<>();
        putIfSet(values, PROVIDER, env.get("RENOVA_AI_PROVIDER"));
        putIfSet(values, MODEL, env.get("RENOVA_AI_MODEL"));
        putIfSet(values, EFFORT, env.get("RENOVA_AI_EFFORT"));
        putIfSet(values, FALLBACKS, env.get("RENOVA_AI_FALLBACKS"));
        putIfSet(values, RAG, env.get("RENOVA_RAG"));
        putIfSet(values, RAG_BUDGET, env.get("RENOVA_RAG_BUDGET"));
        for (AiProviderFactory factory : registry.aiProviders()) {
            putIfSet(values, apiKeyKey(factory.name()), env.get(factory.apiKeyEnvironmentVariable()));
            if (factory.baseUrlEnvironmentVariable() != null) {
                putIfSet(values, baseUrlKey(factory.name()), env.get(factory.baseUrlEnvironmentVariable()));
            }
        }
        return values;
    }

    static String apiKeyKey(String provider) {
        return provider + ".apiKey";
    }

    static String baseUrlKey(String provider) {
        return provider + ".baseUrl";
    }

    String provider() {
        return settings.get(PROVIDER).orElse(NoAiProvider.NAME);
    }

    AiSettings aiSettings() {
        String provider = provider();
        if (NoAiProvider.NAME.equals(provider)) {
            return AiSettings.NONE;
        }
        AiProviderFactory factory = registry.aiProvider(provider).orElseThrow(() -> new IllegalArgumentException(
                "Unknown AI provider '" + provider + "'. Installed: "
                        + registry.aiProviders().stream().map(AiProviderFactory::name).toList() + " (or none)"));
        Map<String, String> options = new LinkedHashMap<>();
        settings.get(EFFORT).ifPresent(v -> options.put("effort", v));
        settings.get(FALLBACKS).ifPresent(v -> options.put("fallbacks", v));
        return new AiSettings(provider,
                settings.get(MODEL).orElse(factory.defaultModel()),
                Secret.of(settings.get(apiKeyKey(provider)).orElse(null)),
                settings.get(baseUrlKey(provider)).orElse(null),
                options);
    }

    /**
     * Retrieval for AI requests: the --rag/--no-rag flag when given, else {@code rag.enabled} (on by
     * default), with {@code rag.budget} as the share of each request it may use.
     */
    RagSettings ragSettings(Boolean flag) {
        boolean enabled = flag != null ? flag : settings.get(RAG).map(v -> {
            if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) {
                throw new IllegalArgumentException(RAG + " must be true or false, not '" + v + "'");
            }
            return Boolean.parseBoolean(v);
        }).orElse(true);
        double budget;
        try {
            budget = settings.get(RAG_BUDGET).map(Double::parseDouble).orElse(RagSettings.DEFAULT_BUDGET);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(RAG_BUDGET + " must be a number such as 0.3");
        }
        return new RagSettings(enabled, budget);
    }

    /** "anthropic / claude-opus-5-5, key sk-ant-…9f3a from environment": safe to print. */
    String describe() {
        AiSettings ai = aiSettings();
        if (NoAiProvider.NAME.equals(ai.provider())) {
            return "none (AI steps are reported as manual work)";
        }
        String keySource = find(apiKeyKey(ai.provider())).map(Settings.Value::source).orElse("not configured");
        return ai.provider() + " / " + ai.model() + ", key " + (ai.apiKey() == null ? "missing" : ai.apiKey().masked())
                + " from " + keySource;
    }

    Optional<Settings.Value> find(String key) {
        return settings.find(key);
    }

    UserConfig userConfig() {
        return userConfig;
    }

    PluginRegistry registry() {
        return registry;
    }

    private static void putIfSet(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }
}
