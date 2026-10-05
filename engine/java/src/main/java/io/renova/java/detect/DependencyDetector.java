package io.renova.java.detect;

import io.renova.core.model.Finding;
import io.renova.core.playbook.Params;
import io.renova.core.playbook.Rule;
import io.renova.core.scan.ScanContext;
import io.renova.core.spi.Detector;
import io.renova.core.spi.DetectorFactory;
import io.renova.core.util.Versions;
import io.renova.java.PomReader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code type: dependency, coordinates: ["javax.servlet:*", "org.springframework:spring-*"], versionBelow?: "6",
 * scopeNot?: provided, packaging?: war} — one finding per declared dependency in any Maven pom
 * (including variants such as pom.jboss.xml) or Gradle build file. Coordinates are
 * {@code groupId:artifactId} globs. {@code scopeNot} reports only real (not managed) dependencies
 * whose scope differs (no scope counts as compile); {@code packaging} limits the check to modules of
 * that packaging. Both apply to Maven only.
 */
public final class DependencyDetector implements DetectorFactory {

    private static final Pattern GRADLE_DEP = Pattern.compile("(['\"])([\\w.\\-]+):([\\w.\\-]+):([^'\":@]+)[^'\"]*\\1");

    @Override
    public String type() {
        return "dependency";
    }

    @Override
    public Detector create(Rule rule) {
        Params params = rule.detectParams();
        List<Pattern> coordinates = params.requiredStrings("coordinates").stream().map(DependencyDetector::glob).toList();
        String versionBelow = params.optString("versionBelow").orElse(null);
        String scopeNot = params.optString("scopeNot").orElse(null);
        String packaging = params.optString("packaging").orElse(null);
        boolean mavenOnly = scopeNot != null || packaging != null;
        return ctx -> {
            List<Finding> findings = new ArrayList<>();
            for (Path pom : ctx.files("**/pom*.xml")) {
                PomReader.Pom read = readPom(ctx, pom);
                if (packaging != null && !packaging.equals(read.packaging())) {
                    continue;
                }
                for (PomReader.Dependency d : read.dependencies()) {
                    if (scopeNot != null && (d.managed() || scopeNot.equals(d.scope() == null ? "compile" : d.scope()))) {
                        continue;
                    }
                    if (matches(coordinates, versionBelow, d.groupId(), d.artifactId(), d.version())) {
                        findings.add(ctx.finding(rule, pom, lineOf(ctx, pom, "<artifactId>" + d.artifactId() + "</artifactId>"),
                                d.coordinates() + (scopeNot == null ? "" : " scope " + (d.scope() == null ? "compile" : d.scope()))));
                    }
                }
            }
            if (mavenOnly) {
                return findings;
            }
            for (Path gradle : ctx.files(List.of("**/build.gradle", "**/build.gradle.kts"))) {
                List<String> lines = ctx.lines(gradle);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = GRADLE_DEP.matcher(lines.get(i));
                    while (m.find()) {
                        if (matches(coordinates, versionBelow, m.group(2), m.group(3), m.group(4))) {
                            findings.add(ctx.finding(rule, gradle, i + 1, m.group(2) + ":" + m.group(3) + ":" + m.group(4)));
                        }
                    }
                }
            }
            return findings;
        };
    }

    private static boolean matches(List<Pattern> coordinates, String versionBelow, String group, String artifact, String version) {
        String ga = group + ":" + artifact;
        if (coordinates.stream().noneMatch(p -> p.matcher(ga).matches())) {
            return false;
        }
        if (versionBelow == null) {
            return true;
        }
        // Unknown or unresolved versions (managed by a parent) are reported: better a false alarm than a miss.
        return version == null || version.contains("${") || Versions.isBelow(version, versionBelow);
    }

    static PomReader.Pom readPom(ScanContext ctx, Path pom) {
        try {
            return PomReader.read(ctx.root().resolve(pom));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static int lineOf(ScanContext ctx, Path file, String needle) {
        List<String> lines = ctx.lines(file);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                return i + 1;
            }
        }
        return 0;
    }

    /** {@code groupId:artifactId} glob ({@code *} wildcard) to a regex. */
    public static Pattern glob(String coordinateGlob) {
        StringBuilder regex = new StringBuilder();
        for (char c : coordinateGlob.toCharArray()) {
            regex.append(c == '*' ? ".*" : Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(regex.toString());
    }
}
