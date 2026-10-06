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
import java.util.regex.Pattern;

/**
 * {@code type: mavenParent, coordinates: ["org.springframework.boot:spring-boot-starter-parent"], versionBelow?: "3"}
 * — one finding per pom whose declared parent matches a {@code groupId:artifactId} glob and, when
 * {@code versionBelow} is given, is older than it. Frameworks such as Spring Boot set their version there,
 * and leave the dependencies themselves without one.
 */
public final class MavenParentDetector implements DetectorFactory {

    @Override
    public String type() {
        return "mavenParent";
    }

    @Override
    public Detector create(Rule rule) {
        Params params = rule.detectParams();
        List<Pattern> coordinates = params.requiredStrings("coordinates").stream().map(DependencyDetector::glob).toList();
        String versionBelow = params.optString("versionBelow").orElse(null);
        return ctx -> {
            List<Finding> findings = new ArrayList<>();
            for (Path pom : ctx.files("**/pom*.xml")) {
                PomReader.Pom read = DependencyDetector.readPom(ctx, pom);
                if (read.parent() == null || coordinates.stream().noneMatch(p -> p.matcher(read.parent()).matches())) {
                    continue;
                }
                String version = read.parentVersion();
                // An unresolved version (a property set elsewhere) is reported: better a false alarm than a miss.
                if (versionBelow == null || version == null || version.contains("${") || Versions.isBelow(version, versionBelow)) {
                    findings.add(ctx.finding(rule, pom, DependencyDetector.lineOf(ctx, pom, "<parent>"), read.parent() + ":" + version));
                }
            }
            return findings;
        };
    }
}
