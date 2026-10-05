package io.renova.core.engine;

import io.renova.core.ai.AiFixer;
import io.renova.core.ai.AiProvider;
import io.renova.core.ai.AiProviderFactory;
import io.renova.core.ai.AiSettings;
import io.renova.core.ai.NoAiProvider;
import io.renova.core.playbook.Playbook;
import io.renova.core.playbook.PlaybookLoader;
import io.renova.core.scan.CoreDetectors;
import io.renova.core.spi.DetectorFactory;
import io.renova.core.spi.EcosystemPlugin;
import io.renova.core.spi.Fixer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Everything discovered at startup: ecosystem plugins, AI provider factories, detectors, fixers and the
 * playbooks the plugins ship with.
 */
public final class PluginRegistry {

    private final Map<String, EcosystemPlugin> plugins = new LinkedHashMap<>();
    private final Map<String, AiProviderFactory> aiProviders = new LinkedHashMap<>();
    private final Map<String, DetectorFactory> detectors = new LinkedHashMap<>();
    private final List<Playbook> playbooks = new ArrayList<>();

    public PluginRegistry(List<EcosystemPlugin> plugins, List<AiProviderFactory> aiProviders) {
        CoreDetectors.all().forEach(this::addDetector);
        for (EcosystemPlugin plugin : plugins) {
            this.plugins.put(plugin.id(), plugin);
            plugin.detectors().forEach(this::addDetector);
            ClassLoader loader = plugin.getClass().getClassLoader();
            plugin.bundledPlaybooks().forEach(r -> playbooks.add(PlaybookLoader.loadResource(loader, r)));
        }
        aiProviders.forEach(p -> this.aiProviders.put(p.name(), p));
    }

    public static PluginRegistry load() {
        ClassLoader loader = PluginRegistry.class.getClassLoader();
        return new PluginRegistry(
                ServiceLoader.load(EcosystemPlugin.class, loader).stream().map(ServiceLoader.Provider::get).toList(),
                ServiceLoader.load(AiProviderFactory.class, loader).stream().map(ServiceLoader.Provider::get).toList());
    }

    private void addDetector(DetectorFactory factory) {
        if (detectors.putIfAbsent(factory.type(), factory) != null) {
            throw new IllegalStateException("Detector type '" + factory.type() + "' registered twice");
        }
    }

    public List<EcosystemPlugin> plugins() {
        return List.copyOf(plugins.values());
    }

    public EcosystemPlugin plugin(String id) {
        EcosystemPlugin plugin = plugins.get(id);
        if (plugin == null) {
            throw new IllegalArgumentException("No plugin for ecosystem '" + id + "'. Installed: " + plugins.keySet());
        }
        return plugin;
    }

    public Optional<DetectorFactory> detectorFactory(String type) {
        return Optional.ofNullable(detectors.get(type));
    }

    /** Core fixers, replaced by a plugin fixer that registers the same strategy. */
    public Optional<Fixer> fixer(String ecosystem, String strategy) {
        Map<String, Fixer> byStrategy = new HashMap<>();
        for (Fixer fixer : List.of(new ReplaceFixer(), new AiFixer())) {
            byStrategy.put(fixer.strategy(), fixer);
        }
        plugin(ecosystem).fixers().forEach(f -> byStrategy.put(f.strategy(), f));
        return Optional.ofNullable(byStrategy.get(strategy));
    }

    public List<AiProviderFactory> aiProviders() {
        return List.copyOf(aiProviders.values());
    }

    public Optional<AiProviderFactory> aiProvider(String name) {
        return Optional.ofNullable(aiProviders.get(name));
    }

    /** Creates a provider with the caller's settings; "none" yields the no-op provider. */
    public AiProvider ai(AiSettings settings) {
        if (settings == null || NoAiProvider.NAME.equals(settings.provider())) {
            return new NoAiProvider();
        }
        AiProviderFactory factory = aiProviders.get(settings.provider());
        if (factory == null) {
            throw new IllegalArgumentException("Unknown AI provider '" + settings.provider() + "'. Installed: "
                    + aiProviders.keySet() + " (or 'none')");
        }
        return factory.create(settings);
    }

    public List<Playbook> playbooks() {
        return List.copyOf(playbooks);
    }

    /** A bundled playbook id, or a path to a YAML file. */
    public Playbook playbook(String idOrPath) {
        Path file = Path.of(idOrPath);
        if (Files.isRegularFile(file)) {
            return PlaybookLoader.load(file);
        }
        return playbooks.stream().filter(p -> p.id().equals(idOrPath)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown playbook '" + idOrPath + "'. Bundled: "
                        + playbooks.stream().map(Playbook::id).toList()));
    }

    /** The playbook to use when none is named: the only one whose plugin accepts the project. */
    public Playbook defaultPlaybook(Path root) {
        List<Playbook> candidates = playbooks.stream()
                .filter(p -> plugins.containsKey(p.ecosystem()) && plugins.get(p.ecosystem()).supports(root))
                .toList();
        if (candidates.size() != 1) {
            throw new IllegalArgumentException(candidates.isEmpty()
                    ? "No bundled playbook supports " + root
                    : "Several playbooks apply, choose one with --playbook: " + candidates.stream().map(Playbook::id).toList());
        }
        return candidates.getFirst();
    }
}
