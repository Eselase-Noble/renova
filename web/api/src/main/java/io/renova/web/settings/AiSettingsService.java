package io.renova.web.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.renova.core.ai.AiProvider;
import io.renova.core.ai.AiProviderFactory;
import io.renova.core.ai.AiSettings;
import io.renova.core.ai.NoAiProvider;
import io.renova.core.config.Secret;
import io.renova.core.engine.PluginRegistry;
import io.renova.core.rag.RagSettings;
import io.renova.web.account.SecretBox;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Each organisation's AI settings and its own API keys. Keys are encrypted at rest ({@link SecretBox}) and
 * only ever returned masked. Nothing is shared between organisations, and the server's own environment and
 * Renova user config are not used, so one organisation can never run on another's key.
 */
@Service
public class AiSettingsService {

    private final PluginRegistry registry;
    private final SecretBox box;
    private final Path dir;
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** What is stored per organisation; keys and base URLs by provider name, keys sealed. */
    public record Stored(String provider, String model, String effort, Boolean rag, Map<String, String> keys,
                  Map<String, String> baseUrls) {
        public Stored {
            keys = keys == null ? Map.of() : Map.copyOf(keys);
            baseUrls = baseUrls == null ? Map.of() : Map.copyOf(baseUrls);
        }
    }

    public AiSettingsService(PluginRegistry registry, SecretBox box, @Value("${renova.data-dir}") Path dataDir) throws IOException {
        this.registry = registry;
        this.box = box;
        this.dir = Files.createDirectories(dataDir.toAbsolutePath().normalize().resolve("accounts").resolve("settings"));
    }

    /** What the console shows. */
    public record View(String provider, String model, String effort, boolean rag, List<ProviderView> providers) {
    }

    public record ProviderView(String name, String displayName, String defaultModel, boolean keyConfigured, String key,
                               String baseUrl) {
    }

    public View view(String organisationId) throws IOException {
        Stored s = read(organisationId);
        List<ProviderView> providers = new ArrayList<>();
        for (AiProviderFactory f : registry.aiProviders()) {
            String sealed = s.keys().get(f.name());
            providers.add(new ProviderView(f.name(), f.displayName(), f.defaultModel(), sealed != null,
                    sealed == null ? null : Secret.of(box.open(sealed)).masked(), s.baseUrls().get(f.name())));
        }
        return new View(s.provider() == null ? NoAiProvider.NAME : s.provider(), s.model(), s.effort(),
                s.rag() == null || s.rag(), providers);
    }

    /** Values: ai.provider, ai.model, ai.effort, rag.enabled, and PROVIDER.baseUrl; an empty value clears it. */
    public synchronized void update(String organisationId, Map<String, String> values) throws IOException {
        Stored s = read(organisationId);
        String provider = s.provider();
        String model = s.model();
        String effort = s.effort();
        Boolean rag = s.rag();
        Map<String, String> baseUrls = new LinkedHashMap<>(s.baseUrls());
        for (Map.Entry<String, String> e : values.entrySet()) {
            String value = e.getValue() == null || e.getValue().isBlank() ? null : e.getValue().strip();
            switch (e.getKey()) {
                case "ai.provider" -> {
                    if (value != null && !value.equals(NoAiProvider.NAME)) {
                        factory(value);
                    }
                    provider = value;
                }
                case "ai.model" -> model = value;
                case "ai.effort" -> effort = value;
                case "rag.enabled" -> rag = value == null ? null : Boolean.parseBoolean(value);
                default -> {
                    if (e.getKey().endsWith(".baseUrl") && registry.aiProvider(e.getKey().replace(".baseUrl", "")).isPresent()) {
                        if (value != null && !value.matches("https?://\\S+")) {
                            throw new IllegalArgumentException("A base URL must start with http:// or https://");
                        }
                        String name = e.getKey().replace(".baseUrl", "");
                        if (value == null) {
                            baseUrls.remove(name);
                        } else {
                            baseUrls.put(name, value);
                        }
                    } else {
                        throw new IllegalArgumentException("Not an editable setting: " + e.getKey());
                    }
                }
            }
        }
        write(organisationId, new Stored(provider, model, effort, rag, s.keys(), baseUrls));
    }

    public synchronized void setKey(String organisationId, String provider, String key) throws IOException {
        factory(provider);
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("The key is empty");
        }
        Stored s = read(organisationId);
        Map<String, String> keys = new LinkedHashMap<>(s.keys());
        keys.put(provider, box.seal(key.strip()));
        write(organisationId, new Stored(s.provider(), s.model(), s.effort(), s.rag(), keys, s.baseUrls()));
    }

    public synchronized boolean removeKey(String organisationId, String provider) throws IOException {
        factory(provider);
        Stored s = read(organisationId);
        Map<String, String> keys = new LinkedHashMap<>(s.keys());
        boolean removed = keys.remove(provider) != null;
        write(organisationId, new Stored(s.provider(), s.model(), s.effort(), s.rag(), keys, s.baseUrls()));
        return removed;
    }

    /** Verifies the key and model without generating anything. */
    public String check(String organisationId) throws IOException {
        AiSettings ai = aiSettings(organisationId);
        if (ai.equals(AiSettings.NONE)) {
            return "No AI provider selected; AI steps are reported as manual work.";
        }
        try (AiProvider provider = registry.ai(ai)) {
            return provider.check();
        }
    }

    public AiSettings aiSettings(String organisationId) throws IOException {
        Stored s = read(organisationId);
        if (s.provider() == null || s.provider().equals(NoAiProvider.NAME)) {
            return AiSettings.NONE;
        }
        AiProviderFactory factory = factory(s.provider());
        Map<String, String> options = new LinkedHashMap<>();
        if (s.effort() != null) {
            options.put("effort", s.effort());
        }
        String sealed = s.keys().get(s.provider());
        return new AiSettings(s.provider(), s.model() == null ? factory.defaultModel() : s.model(),
                Secret.of(sealed == null ? null : box.open(sealed)), s.baseUrls().get(s.provider()), options);
    }

    public RagSettings ragSettings(String organisationId) throws IOException {
        Stored s = read(organisationId);
        return new RagSettings(s.rag() == null || s.rag(), RagSettings.DEFAULT_BUDGET);
    }

    private AiProviderFactory factory(String provider) {
        return registry.aiProvider(provider).orElseThrow(() -> new IllegalArgumentException("Unknown AI provider: " + provider));
    }

    private Stored read(String organisationId) throws IOException {
        Path file = file(organisationId);
        return Files.exists(file) ? json.readValue(file.toFile(), Stored.class) : new Stored(null, null, null, null, null, null);
    }

    private void write(String organisationId, Stored stored) throws IOException {
        Path file = file(organisationId);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        json.writeValue(tmp.toFile(), stored);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private Path file(String organisationId) {
        if (!organisationId.matches("[a-z0-9-]{1,64}")) {
            throw new IllegalArgumentException("Not an organisation id");
        }
        return dir.resolve(organisationId + ".json");
    }
}
