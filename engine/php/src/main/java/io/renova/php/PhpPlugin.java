package io.renova.php;

import io.renova.core.model.Module;
import io.renova.core.model.ProjectModel;
import io.renova.core.spi.DetectorFactory;
import io.renova.core.spi.EcosystemPlugin;
import io.renova.core.spi.Fixer;
import io.renova.core.spi.RelatedFile;
import io.renova.core.spi.Verifier;
import io.renova.php.detect.ComposerPackageDetector;
import io.renova.php.detect.PhpVersionDetector;
import io.renova.php.fix.ComposerFixer;
import io.renova.php.fix.PhpVerifier;
import io.renova.php.fix.RectorFixer;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The PHP ecosystem: Composer projects, from an older PHP to a chosen one, and Laravel applications from an
 * older Laravel to a chosen one.
 */
public final class PhpPlugin implements EcosystemPlugin {

    public static final String ID = "php";
    /** Folders Composer, npm and the framework fill; nothing in them is the project's own code. */
    private static final Set<String> PRODUCED = Set.of("vendor", "node_modules", ".git", ".renova", ".idea");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "PHP (Composer, Laravel)";
    }

    @Override
    public List<String> projectMarkers() {
        return List.of("composer.json");
    }

    @Override
    public boolean supports(Path root) {
        try {
            return !composerFiles(root).isEmpty();
        } catch (IOException | java.io.UncheckedIOException e) {
            return false;
        }
    }

    /** PHP code without Composer: Renova reads a project's PHP version and dependencies from composer.json. */
    @Override
    public Optional<String> unsupportedReason(Path root) {
        if (supports(root)) {
            return Optional.empty();
        }
        try (java.util.stream.Stream<Path> files = Files.find(root, 4, (p, a) -> a.isRegularFile() && p.toString().endsWith(".php")
                && !produced(root.relativize(p)))) {
            if (files.findAny().isEmpty()) {
                return Optional.empty();
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            return Optional.empty();
        }
        return Optional.of("This PHP project has no composer.json, which is where Renova reads the PHP version a project needs and "
                + "the libraries it uses. Add one (\"composer init\" writes it; list the libraries the code includes by hand), then "
                + "assess the project again.");
    }

    @Override
    public ProjectModel model(Path root) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        List<Module> modules = new ArrayList<>();
        Set<String> phpVersions = new TreeSet<>();
        Set<String> frameworks = new TreeSet<>();
        for (Path file : composerFiles(base)) {
            ComposerFile composer;
            try {
                composer = ComposerFile.read(file);
            } catch (IOException e) {
                continue; // not JSON Composer could read either
            }
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("buildTool", "composer");
            if (composer.php() != null) {
                facts.put("php", composer.php());
                phpVersions.add(composer.php());
            }
            Map<String, String> packages = new LinkedHashMap<>();
            composer.require().keySet().forEach(name -> packages.put(name, composer.version(name)));
            composer.requireDev().keySet().forEach(name -> packages.putIfAbsent(name, composer.version(name)));
            packages.remove("php");
            packages.keySet().removeIf(name -> name.startsWith("ext-") || name.startsWith("lib-"));
            facts.put("packages", packages);
            Map<String, String> constraints = new LinkedHashMap<>(composer.requireDev());
            constraints.putAll(composer.require());
            constraints.keySet().retainAll(packages.keySet());
            facts.put("constraints", constraints);
            String framework = framework(packages);
            facts.put("framework", framework);
            frameworks.add(framework);
            facts.put("locked", !composer.locked().isEmpty());
            Path dir = base.relativize(file.getParent());
            String path = dir.toString().isEmpty() ? "." : dir.toString().replace('\\', '/');
            String name = composer.name() != null ? composer.name() : path.equals(".") ? base.getFileName().toString() : path;
            modules.add(new Module(name, path, base.relativize(file).toString().replace('\\', '/'), facts));
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("buildTools", List.of("composer"));
        facts.put("phpVersions", List.copyOf(phpVersions));
        facts.put("frameworks", List.copyOf(frameworks));
        facts.put("modules", modules.size());
        return new ProjectModel(base, ID, modules, facts);
    }

    /** "laravel 8", "symfony 5", or "none": what the application is built on, with its major version. */
    static String framework(Map<String, String> packages) {
        for (String[] known : new String[][] {{"laravel/framework", "laravel"}, {"laravel/lumen-framework", "lumen"},
                {"symfony/framework-bundle", "symfony"}, {"cakephp/cakephp", "cakephp"}, {"codeigniter4/framework", "codeigniter"},
                {"yiisoft/yii2", "yii"}, {"slim/slim", "slim"}}) {
            if (packages.containsKey(known[0])) {
                String version = packages.get(known[0]);
                return known[1] + (version == null ? "" : " " + version.split("\\.")[0]);
            }
        }
        return "none";
    }

    /** A Laravel application goes to the current Laravel; anything else to a PHP most libraries already support. */
    @Override
    public String recommendedPlaybook(Path root, List<String> candidates) {
        boolean laravel = false;
        try {
            laravel = model(root).modules().stream().anyMatch(m -> String.valueOf(m.fact("framework")).startsWith("laravel"));
        } catch (IOException e) {
            // Falls through to the PHP target.
        }
        String wanted = laravel ? "laravel-13" : "php-to-8.4";
        return candidates.contains(wanted) ? wanted : candidates.getFirst();
    }

    @Override
    public List<DetectorFactory> detectors() {
        return List.of(new PhpVersionDetector(), new ComposerPackageDetector());
    }

    @Override
    public List<Fixer> fixers() {
        return List.of(new RectorFixer(), new ComposerFixer(), new io.renova.php.fix.PhpUnitConfigFixer());
    }

    @Override
    public Optional<Verifier> verifier() {
        return Optional.of(new PhpVerifier());
    }

    /** A source file's related file is the composer.json above it: a missing or outdated library is fixed there. */
    @Override
    public List<RelatedFile> relatedFiles(ProjectModel model, String file) {
        if (file.endsWith("composer.json")) {
            return List.of();
        }
        Module owner = null;
        for (Module m : model.modules()) {
            boolean inside = m.path().equals(".") || file.startsWith(m.path() + "/");
            if (inside && (owner == null || m.path().length() > owner.path().length())) {
                owner = m;
            }
        }
        return owner == null ? List.of() : List.of(new RelatedFile(owner.buildFile(), true, "the project's composer.json"));
    }

    /** A file under tests/ (or test/, Tests/), or one named for a test, as PHPUnit and Pest find them. */
    @Override
    public boolean isTestFile(String file) {
        String path = "/" + file.replace('\\', '/');
        return path.matches(".*/(tests?|Tests?|spec)/.*") || path.endsWith("Test.php");
    }

    @Override
    public List<String> bundledPlaybooks() {
        return List.of("playbooks/php/php-to-8.4.yaml", "playbooks/php/php-to-8.5.yaml", "playbooks/php/php-to-8.3.yaml",
                "playbooks/php/laravel-13.yaml", "playbooks/php/laravel-12.yaml", "playbooks/php/laravel-11.yaml",
                // Add-ons: optional, combined with a target (php-to-8.4+phpunit11).
                "playbooks/php/addons/phpunit11.yaml");
    }

    /** Whether a project-relative path is inside a folder that holds no code of the project's own. */
    public static boolean produced(Path relative) {
        for (Path part : relative) {
            if (PRODUCED.contains(part.toString())) {
                return true;
            }
        }
        String path = relative.toString().replace('\\', '/');
        return path.startsWith("storage/") || path.startsWith("bootstrap/cache/");
    }

    /**
     * The project's composer.json. One in the root folder is the project, and any below it belongs to a
     * fixture or a bundled package; without one, the folders below are looked at (a repository of several projects).
     */
    static List<Path> composerFiles(Path root) throws IOException {
        if (Files.isRegularFile(root.resolve("composer.json"))) {
            return List.of(root.resolve("composer.json"));
        }
        List<Path> found = new ArrayList<>();
        Files.walkFileTree(root, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), 5, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return !dir.equals(root) && PRODUCED.contains(dir.getFileName().toString()) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName().toString().equals("composer.json")) {
                    found.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        found.sort(null);
        return found;
    }
}
