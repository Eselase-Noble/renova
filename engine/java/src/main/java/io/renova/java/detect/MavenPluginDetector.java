package io.renova.java.detect;

import io.renova.core.model.Finding;
import io.renova.core.playbook.Params;
import io.renova.core.playbook.Rule;
import io.renova.core.spi.Detector;
import io.renova.core.spi.DetectorFactory;
import io.renova.core.util.Versions;
import io.renova.java.PomReader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code type: mavenPluginBelow, plugin: maven-war-plugin, version: "3.3.2", groupId?, packaging?: war,
 * whenUndeclared?: true} — one finding per pom that declares the plugin below {@code version}, or,
 * with {@code whenUndeclared}, does not declare a version at all (Maven then picks a default that
 * may be very old).
 */
public final class MavenPluginDetector implements DetectorFactory {

    @Override
    public String type() {
        return "mavenPluginBelow";
    }

    @Override
    public Detector create(Rule rule) {
        Params params = rule.detectParams();
        String artifactId = params.string("plugin");
        String groupId = params.optString("groupId").orElse("org.apache.maven.plugins");
        String minimum = params.string("version");
        String packaging = params.optString("packaging").orElse(null);
        boolean whenUndeclared = Boolean.parseBoolean(params.optString("whenUndeclared").orElse("false"));
        return ctx -> {
            List<Finding> findings = new ArrayList<>();
            for (Path pom : ctx.files("**/pom*.xml")) {
                PomReader.Pom read = DependencyDetector.readPom(ctx, pom);
                if (packaging != null && !packaging.equals(read.packaging())) {
                    continue;
                }
                Optional<String> version = read.plugins().stream()
                        .filter(p -> p.groupId().equals(groupId) && artifactId.equals(p.artifactId()) && p.version() != null)
                        .map(PomReader.Plugin::version)
                        .findFirst();
                int line = DependencyDetector.lineOf(ctx, pom, "<artifactId>" + artifactId + "</artifactId>");
                if (version.isEmpty() && whenUndeclared) {
                    findings.add(ctx.finding(rule, pom, line, artifactId + " version not declared (Maven default is used)"));
                } else if (version.isPresent() && !version.get().contains("${") && Versions.isBelow(version.get(), minimum)) {
                    findings.add(ctx.finding(rule, pom, line, artifactId + " " + version.get() + " < " + minimum));
                }
            }
            return findings;
        };
    }
}
