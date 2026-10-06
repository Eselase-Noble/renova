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

    /**
     * A bundled playbook id, or a path to a YAML file; with add-ons joined by {@code +}, as in
     * {@code java-to-21+junit5+log4j2}, the target combined with them.
     */
    public Playbook playbook(String idOrPath) {
        if (Files.isRegularFile(Path.of(idOrPath))) {
            return PlaybookLoader.load(Path.of(idOrPath));
        }
        String[] parts = idOrPath.split("\\+");
        Playbook base = single(parts[0]);
        if (parts.length == 1) {
            return base;
        }
        List<Playbook> addons = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            Playbook addon = single(parts[i]);
            if (!addon.addon()) {
                throw new IllegalArgumentException("'" + addon.id() + "' is a target, not an add-on. A migration has one target ("
                        + base.id() + ") and any number of add-ons: " + addons().stream().map(Playbook::id).toList());
            }
            addons.add(addon);
        }
        return base.with(addons);
    }

    private Playbook single(String idOrPath) {
        Path file = Path.of(idOrPath);
        if (Files.isRegularFile(file)) {
            return PlaybookLoader.load(file);
        }
        return playbooks.stream().filter(p -> p.id().equals(idOrPath)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown playbook '" + idOrPath + "'. Bundled: "
                        + playbooks.stream().map(Playbook::id).toList()));
    }

    /** The optional add-ons that can be combined with a target. */
    public List<Playbook> addons() {
        return playbooks.stream().filter(Playbook::addon).toList();
    }

    /** The add-ons that would change something in the project: those of its ecosystem, for a picker to offer. */
    public List<Playbook> addonsFor(Path root) {
        return addons().stream().filter(p -> plugins.containsKey(p.ecosystem()) && plugins.get(p.ecosystem()).supports(root)).toList();
    }

    /** The targets the project can be migrated to: those of the ecosystems that recognise it, without add-ons. */
    public List<Playbook> playbooksFor(Path root) {
        return playbooks.stream()
                .filter(p -> !p.addon() && plugins.containsKey(p.ecosystem()) && plugins.get(p.ecosystem()).supports(root))
                .toList();
    }

    /**
     * The playbook to use when none is named: the one the project's ecosystem recommends for it. A project that
     * two ecosystems recognise has no obvious answer, so the user is asked to choose.
     */
    public Playbook defaultPlaybook(Path root) {
        List<Playbook> candidates = playbooksFor(root);
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException(plugins.values().stream().map(p -> p.unsupportedReason(root)).flatMap(Optional::stream)
                    .findFirst().orElse("No bundled playbook supports " + root
                            + ": Renova found no build file it recognises (installed ecosystems: " + plugins.keySet() + ")"));
        }
        List<String> ecosystems = candidates.stream().map(Playbook::ecosystem).distinct().toList();
        if (ecosystems.size() > 1) {
            throw new IllegalArgumentException("Playbooks of several ecosystems apply " + ecosystems + ", choose one with --playbook: "
                    + candidates.stream().map(Playbook::id).toList());
        }
        String id = plugins.get(ecosystems.getFirst()).recommendedPlaybook(root, candidates.stream().map(Playbook::id).toList());
        return candidates.stream().filter(p -> p.id().equals(id)).findFirst().orElse(candidates.getFirst());
    }
}
