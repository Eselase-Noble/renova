package io.renova.dotnet;

import io.renova.core.engine.StageResult;
import io.renova.core.model.Module;
import io.renova.core.model.ProjectModel;
import io.renova.core.spi.DetectorFactory;
import io.renova.core.spi.EcosystemPlugin;
import io.renova.core.spi.Fixer;
import io.renova.core.spi.RelatedFile;
import io.renova.core.spi.Verifier;
import io.renova.dotnet.detect.AssemblyReferenceDetector;
import io.renova.dotnet.detect.LegacyProjectDetector;
import io.renova.dotnet.detect.NamespaceDetector;
import io.renova.dotnet.detect.NamespacePackageDetector;
import io.renova.dotnet.detect.NugetPackageDetector;
import io.renova.dotnet.detect.ProjectKindDetector;
import io.renova.dotnet.detect.TargetFrameworkDetector;
import io.renova.dotnet.fix.DotnetProjectFixer;
import io.renova.dotnet.fix.DotnetVerifier;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The .NET ecosystem: C# and Visual Basic projects (.csproj, .vbproj) and the solutions that group them,
 * from .NET Framework, .NET Core or an older modern .NET to a chosen modern .NET.
 */
public final class DotnetPlugin implements EcosystemPlugin {

    public static final String ID = "dotnet";
    private static final Set<String> NOT_SEARCHED = Set.of("bin", "obj", "packages", ".vs", "TestResults", "node_modules", ".git", ".renova");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return ".NET (C#, Visual Basic)";
    }

    @Override
    public List<String> projectMarkers() {
        return List.of("*.sln", "*.slnx", "*.csproj", "*.vbproj");
    }

    @Override
    public boolean supports(Path root) {
        try {
            return !projectFiles(root).isEmpty();
        } catch (IOException | java.io.UncheckedIOException e) {
            return false;
        }
    }

    @Override
    public ProjectModel model(Path root) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        List<Module> modules = new ArrayList<>();
        Set<String> languages = new TreeSet<>();
        Set<String> frameworks = new TreeSet<>();
        Set<String> kinds = new TreeSet<>();
        for (Path file : projectFiles(base)) {
            ProjectFile project;
            try {
                project = ProjectFile.read(file);
            } catch (IOException e) {
                continue; // not a project file a tool could read either; the build will say so
            }
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("buildTool", "dotnet");
            facts.put("language", project.visualBasic() ? "Visual Basic" : "C#");
            facts.put("sdkStyle", project.sdkStyle());
            String kind = project.kind();
            if (kind.equals("aspnet") && hasFiles(file.getParent(), ".aspx", ".ascx", ".master")) {
                kind = "webforms"; // pages with code behind: nothing in ASP.NET Core takes them as they are
            } else if (kind.equals("aspnet") && hasFiles(file.getParent(), ".svc") && !hasFiles(file.getParent(), ".cshtml", ".vbhtml")) {
                kind = "wcf"; // services IIS hosts from .svc files, and no pages
            }
            facts.put("kind", kind);
            facts.put("test", project.test());
            facts.put("targetFrameworks", project.targetFrameworks());
            facts.put("packages", project.packages().stream().map(ProjectFile.Package::coordinates).toList());
            facts.put("references", project.references().stream().map(ProjectFile.Reference::name).toList());
            facts.put("projectReferences", project.projectReferences());
            Path dir = base.relativize(file.getParent());
            modules.add(new Module(project.name(), dir.toString().isEmpty() ? "." : dir.toString().replace('\\', '/'),
                    base.relativize(file).toString().replace('\\', '/'), facts));
            languages.add(facts.get("language").toString());
            frameworks.addAll(project.targetFrameworks());
            kinds.add(kind);
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("buildTools", List.of("dotnet"));
        facts.put("languages", List.copyOf(languages));
        facts.put("targetFrameworks", List.copyOf(frameworks));
        facts.put("kinds", List.copyOf(kinds));
        facts.put("modules", modules.size());
        try (Stream<Path> files = Files.list(base)) {
            facts.put("solutions", files.map(f -> f.getFileName().toString())
                    .filter(n -> n.endsWith(".sln") || n.endsWith(".slnx")).sorted().toList());
        }
        return new ProjectModel(base, ID, modules, facts);
    }

    /** The newest long-term-support release; the older one stays available for whoever needs it. */
    @Override
    public String recommendedPlaybook(Path root, List<String> candidates) {
        return candidates.contains("dotnet-to-10") ? "dotnet-to-10" : candidates.getFirst();
    }

    /**
     * Build output that came with the project is removed from the copy: {@code obj} holds the restore of the
     * old target framework, which the new build would trip over. Output of the builds to come is kept out of
     * the stages' commits.
     */
    @Override
    public Optional<StageResult> prepare(Path workspace, Map<String, String> options) throws Exception {
        Path exclude = workspace.resolve(".git/info/exclude");
        if (Files.isRegularFile(exclude) && !Files.readString(exclude).contains("obj/")) {
            Files.writeString(exclude, "bin/\nobj/\nTestResults/\n", StandardOpenOption.APPEND);
        }
        int removed = 0;
        for (Path project : projectFiles(workspace)) {
            for (String name : List.of("bin", "obj")) {
                Path dir = project.resolveSibling(name);
                if (Files.isDirectory(dir)) {
                    try (Stream<Path> files = Files.walk(dir)) {
                        for (Path p : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                            Files.delete(p);
                        }
                    }
                    removed++;
                }
            }
        }
        return removed == 0 ? Optional.empty() : Optional.of(new StageResult("prepare", StageResult.Status.APPLIED,
                removed + " folder(s) of earlier build output removed from the copy", List.of()));
    }

    @Override
    public List<DetectorFactory> detectors() {
        return List.of(new TargetFrameworkDetector(), new LegacyProjectDetector(), new ProjectKindDetector(),
                new NugetPackageDetector(), new NamespaceDetector(), new NamespacePackageDetector(), new AssemblyReferenceDetector(),
                new io.renova.dotnet.detect.WindowsReferenceDetector());
    }

    @Override
    public List<Fixer> fixers() {
        return List.of(new DotnetProjectFixer(), new io.renova.dotnet.fix.DotnetSourceFixer(), new io.renova.dotnet.fix.CoreWcfHostFixer());
    }

    @Override
    public Optional<Verifier> verifier() {
        return Optional.of(new DotnetVerifier());
    }

    @Override
    public Optional<io.renova.core.behaviour.BehaviourRunner> behaviourRunner() {
        return Optional.of(new io.renova.dotnet.behaviour.DotnetBehaviourRunner());
    }

    /** A source file's related file is its project file: a missing package is fixed there, not in the code. */
    @Override
    public List<RelatedFile> relatedFiles(ProjectModel model, String file) {
        String lower = file.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".csproj") || lower.endsWith(".vbproj") || model.modules().isEmpty()) {
            return List.of();
        }
        Module owner = null;
        for (Module m : model.modules()) {
            boolean inside = m.path().equals(".") || file.startsWith(m.path() + "/");
            if (inside && (owner == null || m.path().length() > owner.path().length())) {
                owner = m;
            }
        }
        return owner == null ? List.of() : List.of(new RelatedFile(owner.buildFile(), true, "project file of " + owner.name()));
    }

    /**
     * By the conventions .NET projects follow: a folder named for tests (Tests, Billing.Tests, BillingTests,
     * UnitTests) or a source file whose name ends in Test or Tests.
     */
    @Override
    public boolean isTestFile(String file) {
        String[] parts = file.replace('\\', '/').split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].matches("(?i:tests?)|.*[._-](?i:tests?)|.*[a-z0-9]Tests?")) {
                return true;
            }
        }
        return parts[parts.length - 1].matches(".*Tests?\\.(cs|vb)");
    }

    @Override
    public List<String> bundledPlaybooks() {
        return List.of("playbooks/dotnet/dotnet-to-10.yaml", "playbooks/dotnet/dotnet-to-8.yaml");
    }

    /** Whether the project has a file with one of the extensions, outside what a build or a tool produced. */
    private static boolean hasFiles(Path projectDir, String... extensions) throws IOException {
        try (Stream<Path> files = Files.walk(projectDir, 6)) {
            return files.anyMatch(f -> {
                String name = f.getFileName().toString().toLowerCase(Locale.ROOT);
                return java.util.Arrays.stream(extensions).anyMatch(name::endsWith) && !Sources.produced(projectDir.relativize(f));
            });
        }
    }

    static List<Path> projectFiles(Path root) throws IOException {
        List<Path> found = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return !dir.equals(root) && NOT_SEARCHED.contains(dir.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".csproj") || name.endsWith(".vbproj")) {
                    found.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        found.sort(null);
        return found;
    }
}
