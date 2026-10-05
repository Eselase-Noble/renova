package io.renova.java.detect;

import io.renova.core.model.Finding;
import io.renova.core.playbook.Rule;
import io.renova.core.scan.ScanContext;
import io.renova.core.spi.Detector;
import io.renova.core.spi.DetectorFactory;
import io.renova.java.PomReader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code type: duplicateDependency} — one finding per real dependency declared again in the same pom
 * (same groupId, artifactId, type and classifier). Maven only warns about these, but the copies can
 * disagree on version and scope, so which one applies is easy to misread. Recipes that add a
 * dependency without noticing a renamed existing declaration leave them behind.
 */
public final class DuplicateDependencyDetector implements DetectorFactory {

    @Override
    public String type() {
        return "duplicateDependency";
    }

    @Override
    public Detector create(Rule rule) {
        return ctx -> {
            List<Finding> findings = new ArrayList<>();
            for (Path pom : ctx.files("**/pom*.xml")) {
                Set<String> seen = new HashSet<>();
                for (PomReader.Dependency d : DependencyDetector.readPom(ctx, pom).dependencies()) {
                    if (!d.managed() && !seen.add(d.key())) {
                        findings.add(ctx.finding(rule, ScanContext.toProjectPath(pom),
                                lastLineOf(ctx, pom, "<artifactId>" + d.artifactId() + "</artifactId>"),
                                d.coordinates() + " is declared more than once"));
                    }
                }
            }
            return findings;
        };
    }

    private static int lastLineOf(ScanContext ctx, Path file, String needle) {
        List<String> lines = ctx.lines(file);
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).contains(needle)) {
                return i + 1;
            }
        }
        return 0;
    }
}
