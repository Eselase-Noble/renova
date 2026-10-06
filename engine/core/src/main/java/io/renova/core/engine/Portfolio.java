package io.renova.core.engine;

import io.renova.core.model.Severity;
import io.renova.core.playbook.FixSpec;
import io.renova.core.playbook.Playbook;
import io.renova.core.scan.ScanContext;
import io.renova.core.spi.EcosystemPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The assessment of every project under one folder: what an organisation with many legacy systems looks at
 * first, to see how much work there is and where to start. Nothing is modified.
 */
public final class Portfolio {

    /**
     * One project's headline figures.
     *
     * @param path           relative to the folder that was scanned
     * @param playbook       the suggested target's id; null when the project could not be assessed
     * @param automationRate the share of the findings that need no human decision
     * @param aiSteps        plan steps for AI, checked by the build
     * @param manualSteps    plan steps left to a person
     * @param error          why it could not be assessed; null otherwise
     */
    public record Entry(String name, String path, String ecosystem, String playbook, String target, Object javaVersions,
                        int findings, int steps, int blockers, int aiSteps, int manualSteps, double automationRate, String error) {

        /** Ready to migrate without anyone deciding anything: every step is a recipe, a rule or a build-file edit. */
        public boolean fullyAutomatic() {
            return error == null && aiSteps == 0 && manualSteps == 0;
        }
    }

    /** @param entries easiest first: fully automatic projects, then by how much is left to AI and people */
    public record Result(Path root, List<Entry> entries) {

        public int findings() {
            return entries.stream().mapToInt(Entry::findings).sum();
        }

        public long fullyAutomatic() {
            return entries.stream().filter(Entry::fullyAutomatic).count();
        }

        /** The automation rate over all findings, so a large project counts for more than a small one. */
        public double automationRate() {
            double automated = entries.stream().mapToDouble(e -> e.findings() * e.automationRate()).sum();
            return findings() == 0 ? 1 : automated / findings();
        }
    }

    private static final int DEFAULT_DEPTH = 4;

    private final PluginRegistry registry;

    public Portfolio(PluginRegistry registry) {
        this.registry = registry;
    }

    public Result assess(Path folder, Consumer<String> progress) throws IOException {
        return assess(folder, DEFAULT_DEPTH, progress);
    }

    /** @param depth how many folders down to look for projects */
    public Result assess(Path folder, int depth, Consumer<String> progress) throws IOException {
        Path root = folder.toAbsolutePath().normalize();
        List<Entry> entries = new ArrayList<>();
        for (Path project : projects(root, depth)) {
            String relative = root.relativize(project).toString().replace('\\', '/');
            progress.accept("Assessing " + (relative.isEmpty() ? project.getFileName() : relative));
            entries.add(assessOne(project, relative.isEmpty() ? "." : relative));
        }
        entries.sort(Comparator.comparing((Entry e) -> e.error() != null)
                .thenComparing(e -> !e.fullyAutomatic())
                .thenComparingInt(e -> e.manualSteps() * 10 + e.aiSteps())
                .thenComparing(Entry::name));
        return new Result(root, entries);
    }

    /**
     * Project roots under the folder: a directory holding a file that marks a project of an installed ecosystem
     * (a pom.xml, a build.gradle). The search does not look inside a project it has found, so the modules of a
     * multi-module build are part of their parent, not projects of their own.
     */
    List<Path> projects(Path root, int depth) throws IOException {
        List<String> markers = registry.plugins().stream().flatMap(p -> p.projectMarkers().stream()).distinct().toList();
        List<Path> found = new ArrayList<>();
        collect(root, depth, markers, found);
        found.sort(Comparator.naturalOrder());
        return found;
    }

    private static void collect(Path dir, int depth, List<String> markers, List<Path> found) throws IOException {
        if (markers.stream().anyMatch(m -> Files.isRegularFile(dir.resolve(m)))) {
            found.add(dir);
            return;
        }
        if (depth == 0) {
            return;
        }
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : children.filter(Files::isDirectory).sorted().toList()) {
                String name = child.getFileName().toString();
                if (!name.startsWith(".") && !ScanContext.IGNORED_DIRS.contains(name) && !Files.isSymbolicLink(child)) {
                    collect(child, depth - 1, markers, found);
                }
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            // A folder that cannot be read holds no project we can assess.
        }
    }

    private Entry assessOne(Path project, String relative) {
        String name = project.getFileName().toString();
        try {
            Playbook playbook = registry.defaultPlaybook(project);
            AnalysisResult analysis = new Analyzer(registry).analyze(project, playbook);
            MigrationPlan plan = new Planner().plan(analysis);
            EcosystemPlugin plugin = registry.plugin(playbook.ecosystem());
            return new Entry(name, relative, plugin.id(), playbook.id(), playbook.name(), analysis.project().facts().get("javaVersions"),
                    analysis.findings().size(), plan.steps().size(),
                    (int) plan.steps().stream().filter(s -> s.rule().severity() == Severity.BLOCKER).count(),
                    plan.steps(FixSpec.AI).size(), plan.steps(FixSpec.MANUAL).size(), plan.automationRate(), null);
        } catch (Exception e) {
            return new Entry(name, relative, null, null, null, null, 0, 0, 0, 0, 0, 0,
                    e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /** The portfolio as a Markdown report. */
    public static String markdown(Result r) {
        StringBuilder md = new StringBuilder("# Portfolio assessment: " + r.root().getFileName() + "\n\n");
        long assessed = r.entries().stream().filter(e -> e.error() == null).count();
        md.append("| | |\n|---|---|\n")
                .append("| Projects | ").append(r.entries().size()).append(" |\n")
                .append("| Findings | ").append(r.findings()).append(" |\n")
                .append("| Automated | ").append(Math.round(r.automationRate() * 100)).append("% of findings need no human decision |\n")
                .append("| Fully automatic | ").append(r.fullyAutomatic()).append(" of ").append(assessed)
                .append(" projects migrate with recipes and rules alone |\n\n");
        md.append("Easiest first. \"AI\" steps are edits an AI proposes and the build checks; \"person\" steps are decisions left to your team.\n\n");
        md.append("| Project | Suggested target | Findings | Automated | Blockers | AI steps | Person steps |\n|---|---|---|---|---|---|---|\n");
        for (Entry e : r.entries()) {
            if (e.error() != null) {
                md.append("| ").append(e.path()).append(" | could not be assessed: ").append(e.error().replace('|', '/').replace('\n', ' '))
                        .append(" | | | | | |\n");
            } else {
                md.append("| ").append(e.path()).append(" | ").append(e.target()).append(" | ").append(e.findings()).append(" | ")
                        .append(Math.round(e.automationRate() * 100)).append("% | ").append(e.blockers()).append(" | ")
                        .append(e.aiSteps()).append(" | ").append(e.manualSteps()).append(" |\n");
            }
        }
        return md.toString();
    }

    /** The portfolio as CSV, for a spreadsheet. */
    public static String csv(Result r) {
        StringBuilder csv = new StringBuilder("project,path,ecosystem,playbook,target,findings,steps,blockers,ai_steps,person_steps,automation_rate,error\n");
        for (Entry e : r.entries()) {
            csv.append(String.join(",", quote(e.name()), quote(e.path()), quote(e.ecosystem()), quote(e.playbook()), quote(e.target()),
                    String.valueOf(e.findings()), String.valueOf(e.steps()), String.valueOf(e.blockers()), String.valueOf(e.aiSteps()),
                    String.valueOf(e.manualSteps()), String.format(java.util.Locale.ROOT, "%.3f", e.automationRate()), quote(e.error())))
                    .append('\n');
        }
        return csv.toString();
    }

    private static String quote(String value) {
        return value == null ? "" : "\"" + value.replace("\"", "\"\"").replace('\n', ' ') + "\"";
    }
}
