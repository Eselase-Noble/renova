package io.renova.java.detect;

import io.renova.core.model.Finding;
import io.renova.core.playbook.Rule;
import io.renova.core.scan.ScanContext;
import io.renova.core.spi.Detector;
import io.renova.core.spi.DetectorFactory;
import io.renova.java.PomReader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code type: dependencyWithoutVersion} — finds real dependencies declared without a version that no
 * dependencyManagement in the module's parent chain manages, when a sibling artifact of the same family
 * is managed. Typical after a recipe renames an artifact in a module but renames the parent's managed
 * entry differently ({@code commons-fileupload2-jakarta-servlet5} in the module, {@code -servlet6} in
 * the parent): Maven then fails with "dependencies.dependency.version is missing".
 *
 * <p>Artifacts are of the same family when they share a groupId and differ only in the last
 * {@code -segment} of the artifactId. The finding carries the sibling's version in
 * {@link Finding#data()} for the {@code setVersion} fix. Nothing is reported when the version cannot be
 * known: the parent chain leaves the project, imports a BOM, or no single family version exists. The
 * build then reports the error and AI repair or a person resolves it.
 */
public final class UnversionedDependencyDetector implements DetectorFactory {

    @Override
    public String type() {
        return "dependencyWithoutVersion";
    }

    @Override
    public Detector create(Rule rule) {
        return ctx -> {
            List<Finding> findings = new ArrayList<>();
            for (Path pom : ctx.files("**/pom*.xml")) {
                PomReader.Pom read = DependencyDetector.readPom(ctx, pom);
                List<PomReader.Dependency> managed = managedInChain(ctx, pom, read);
                if (managed == null) {
                    continue;
                }
                for (PomReader.Dependency d : read.dependencies()) {
                    if (d.managed() || d.version() != null || managed.stream().anyMatch(m -> sameArtifact(m, d))) {
                        continue;
                    }
                    String family = family(d.artifactId());
                    Set<String> versions = new TreeSet<>();
                    PomReader.Dependency sibling = null;
                    for (PomReader.Dependency m : managed) {
                        if (family != null && m.groupId().equals(d.groupId()) && family.equals(family(m.artifactId()))
                                && m.version() != null && !m.version().contains("${")) {
                            versions.add(m.version());
                            sibling = m;
                        }
                    }
                    if (versions.size() != 1) {
                        continue;
                    }
                    Map<String, String> data = new LinkedHashMap<>();
                    data.put("groupId", d.groupId());
                    data.put("artifactId", d.artifactId());
                    data.put("version", sibling.version());
                    findings.add(ctx.finding(rule, ScanContext.toProjectPath(pom),
                            DependencyDetector.lineOf(ctx, pom, "<artifactId>" + d.artifactId() + "</artifactId>"),
                            d.groupId() + ":" + d.artifactId() + " has no version and is not managed; the managed "
                                    + sibling.artifactId() + " is " + sibling.version(), data));
                }
            }
            return findings;
        };
    }

    /**
     * Managed dependencies of this pom and its parents within the project, or null when they cannot be
     * known: a parent outside the project, or a BOM import (scope {@code import}) anywhere in the chain.
     */
    private static List<PomReader.Dependency> managedInChain(ScanContext ctx, Path pom, PomReader.Pom read) {
        List<PomReader.Dependency> managed = new ArrayList<>();
        Path current = pom;
        PomReader.Pom currentPom = read;
        while (true) {
            for (PomReader.Dependency d : currentPom.dependencies()) {
                if (d.managed()) {
                    if ("import".equals(d.scope())) {
                        return null;
                    }
                    managed.add(d);
                }
            }
            if (currentPom.parent() == null) {
                return managed;
            }
            Path dir = current.getParent();
            if (dir == null) {
                return null;
            }
            Path parentPom = dir.getParent() == null ? Path.of("pom.xml") : dir.getParent().resolve("pom.xml");
            if (!ctx.files(ScanContext.toProjectPath(parentPom)).contains(parentPom)) {
                return null;
            }
            PomReader.Pom parent = DependencyDetector.readPom(ctx, parentPom);
            if (!(parent.groupId() + ":" + parent.artifactId()).equals(currentPom.parent())) {
                return null;
            }
            current = parentPom;
            currentPom = parent;
        }
    }

    private static boolean sameArtifact(PomReader.Dependency a, PomReader.Dependency b) {
        return a.groupId().equals(b.groupId()) && a.artifactId().equals(b.artifactId());
    }

    /** The artifactId without its last {@code -segment}, or null when it has none. */
    static String family(String artifactId) {
        int dash = artifactId.lastIndexOf('-');
        return dash <= 0 ? null : artifactId.substring(0, dash);
    }
}
