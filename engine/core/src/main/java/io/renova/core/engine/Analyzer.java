package io.renova.core.engine;

import io.renova.core.model.Finding;
import io.renova.core.model.ProjectModel;
import io.renova.core.playbook.Playbook;
import io.renova.core.playbook.Rule;
import io.renova.core.scan.ScanContext;
import io.renova.core.spi.Detector;
import io.renova.core.spi.DetectorFactory;
import io.renova.core.spi.EcosystemPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs every rule of a playbook against a project. Read-only: never modifies the project. */
public final class Analyzer {

    private final PluginRegistry registry;

    public Analyzer(PluginRegistry registry) {
        this.registry = registry;
    }

    public AnalysisResult analyze(Path root, Playbook playbook) throws IOException {
        EcosystemPlugin plugin = registry.plugin(playbook.ecosystem());
        if (!plugin.supports(root)) {
            throw new IllegalArgumentException(root + " is not a " + plugin.displayName() + " project");
        }
        ProjectModel model = plugin.model(root);
        ScanContext context = ScanContext.of(model);

        // Build every detector first so a broken playbook fails before any scanning.
        Map<Rule, Detector> detectors = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        for (Rule rule : playbook.rules()) {
            DetectorFactory factory = registry.detectorFactory(rule.detectorType()).orElse(null);
            if (factory == null) {
                warnings.add("Rule '" + rule.id() + "' skipped: no detector of type '" + rule.detectorType() + "'");
                continue;
            }
            detectors.put(rule, factory.create(rule));
        }

        List<Finding> findings = new ArrayList<>();
        detectors.forEach((rule, detector) -> {
            try {
                findings.addAll(detector.detect(context));
            } catch (RuntimeException e) {
                warnings.add("Rule '" + rule.id() + "' failed: " + e.getMessage());
            }
        });
        return new AnalysisResult(model, playbook, findings, warnings);
    }
}
