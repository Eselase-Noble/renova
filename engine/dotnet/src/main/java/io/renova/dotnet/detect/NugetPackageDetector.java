package io.renova.dotnet.detect;

import io.renova.core.model.Finding;
import io.renova.core.model.Module;
import io.renova.core.playbook.Params;
import io.renova.core.playbook.Rule;
import io.renova.core.spi.Detector;
import io.renova.core.spi.DetectorFactory;
import io.renova.core.util.Versions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code type: nugetPackage, ids: [NUnit, "Microsoft.AspNetCore.*"], versionBelow?: "3.13", unless?: [Other.Package]}
 * — one finding per project and package it uses (PackageReference or packages.config) that matches an id and
 * is older than {@code versionBelow}. An id may end in {@code *}. A version held in a property is reported:
 * better a false alarm than a miss. With {@code unless}, projects that use one of those packages are skipped.
 */
public final class NugetPackageDetector implements DetectorFactory {

    @Override
    public String type() {
        return "nugetPackage";
    }

    @Override
    public Detector create(Rule rule) {
        Params params = rule.detectParams();
        List<String> ids = params.requiredStrings("ids").stream().map(i -> i.toLowerCase(Locale.ROOT)).toList();
        List<String> unless = params.strings("unless").stream().map(i -> i.toLowerCase(Locale.ROOT)).toList();
        String versionBelow = params.optString("versionBelow").orElse(null);
        return ctx -> {
            List<Finding> findings = new ArrayList<>();
            for (Module module : ctx.model().modules()) {
                if (!(module.fact("packages") instanceof List<?> packages)) {
                    continue;
                }
                List<String[]> used = packages.stream().map(p -> p.toString().split(":", 2)).toList();
                if (used.stream().anyMatch(p -> unless.contains(p[0].toLowerCase(Locale.ROOT)))) {
                    continue;
                }
                for (String[] p : used) {
                    String version = p.length > 1 ? p[1] : null;
                    boolean old = versionBelow == null || version == null || !version.matches("\\d.*")
                            || Versions.isBelow(version, versionBelow);
                    if (matches(p[0], ids) && old) {
                        findings.add(ctx.finding(rule, module.buildFile(), 0, p[0] + (version == null ? "" : " " + version),
                                Map.of("package", p[0], "version", version == null ? "" : version)));
                    }
                }
            }
            return findings;
        };
    }

    static boolean matches(String id, List<String> patterns) {
        String lower = id.toLowerCase(Locale.ROOT);
        return patterns.stream().anyMatch(p -> p.endsWith("*") ? lower.startsWith(p.substring(0, p.length() - 1)) : lower.equals(p));
    }
}
