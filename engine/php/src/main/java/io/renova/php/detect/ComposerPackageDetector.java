package io.renova.php.detect;

import io.renova.core.model.Finding;
import io.renova.core.model.Module;
import io.renova.core.playbook.Params;
import io.renova.core.playbook.Rule;
import io.renova.core.spi.Detector;
import io.renova.core.spi.DetectorFactory;
import io.renova.core.util.Versions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code type: composerPackage, names: [laravel/framework, "symfony/*"], versionBelow?: "12.0",
 * constraintBelow?: "2.10", unless?: [other/package]} — one finding per composer.json and package it requires
 * (in either section) that matches a name. With {@code versionBelow}, only where the version in use is older:
 * the one composer.lock installed, else the lowest the constraint allows. With {@code constraintBelow}, only
 * where the constraint itself starts lower, whatever is installed: {@code ^2.8} cannot reach a release that
 * {@code ^2.10|^3.0} can. A name may end in {@code *}. With {@code unless}, a project that requires one of those
 * packages is skipped; with {@code except}, packages matching one of those names are (for "symfony/*" without
 * the packages that are not released with the framework).
 */
public final class ComposerPackageDetector implements DetectorFactory {

    @Override
    public String type() {
        return "composerPackage";
    }

    @Override
    public Detector create(Rule rule) {
        Params params = rule.detectParams();
        List<String> names = params.requiredStrings("names");
        List<String> unless = params.strings("unless");
        List<String> except = params.strings("except");
        String versionBelow = params.optString("versionBelow").orElse(null);
        String constraintBelow = params.optString("constraintBelow").orElse(null);
        return ctx -> {
            List<Finding> findings = new ArrayList<>();
            for (Module module : ctx.model().modules()) {
                if (!(module.fact("packages") instanceof Map<?, ?> packages)
                        || packages.keySet().stream().anyMatch(p -> matches(p.toString(), unless))) {
                    continue;
                }
                Map<?, ?> constraints = module.fact("constraints") instanceof Map<?, ?> c ? c : Map.of();
                for (Map.Entry<?, ?> p : packages.entrySet()) {
                    String version = p.getValue() == null ? null : p.getValue().toString();
                    boolean old = versionBelow == null || version == null || Versions.isBelow(version, versionBelow);
                    if (constraintBelow != null) {
                        String lowest = io.renova.php.Constraints.lowest(String.valueOf(constraints.get(p.getKey())));
                        old = lowest == null || Versions.isBelow(lowest, constraintBelow);
                    }
                    if (matches(p.getKey().toString(), names) && !matches(p.getKey().toString(), except) && old) {
                        findings.add(ctx.finding(rule, module.buildFile(), 0, p.getKey() + (version == null ? "" : " " + version),
                                Map.of("package", p.getKey().toString(), "version", version == null ? "" : version)));
                    }
                }
            }
            return findings;
        };
    }

    static boolean matches(String name, List<String> patterns) {
        return patterns.stream().anyMatch(p -> p.endsWith("*") ? name.startsWith(p.substring(0, p.length() - 1)) : name.equals(p));
    }
}
