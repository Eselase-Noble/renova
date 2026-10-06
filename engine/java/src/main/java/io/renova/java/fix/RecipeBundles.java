package io.renova.java.fix;

import io.renova.core.engine.MigrationContext;
import io.renova.core.util.Proc;
import io.renova.core.util.Versions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Recipes that an artifact ships as YAML files OpenRewrite does not find by itself, one file per release
 * (Quarkus publishes its upgrade recipes this way). A playbook names such a bundle under
 * {@code settings.openrewrite.bundles}:
 * <pre>
 * - recipe: io.renova.java.QuarkusUpdate          # the name the playbook's rules use
 *   artifact: io.quarkus:quarkus-update-recipes:1.15.2
 *   path: quarkus-updates/core/                   # the folder inside the jar
 *   upTo: "3.33"                                  # files for later releases are left out
 *   first:                                        # recipes to run before the bundled ones
 *     - org.openrewrite.maven.ChangePropertyValue: { key: quarkus.platform.version, newValue: 3.33.4 }
 * </pre>
 * A bundle without an artifact is a recipe the playbook composes itself from the list under {@code first}.
 * The files are joined into one configuration file with a recipe of the given name that runs them in release
 * order; the recipe run is pointed at that file.
 */
final class RecipeBundles {

    private static final Pattern NAME = Pattern.compile("(?m)^name:\\s*(\\S+)\\s*$");
    private static final Pattern RELEASE = Pattern.compile("^(\\d+(?:\\.\\d+)*)");
    private static final Duration TIMEOUT = Duration.ofMinutes(20);

    private RecipeBundles() {
    }

    record Bundle(String recipe, String artifact, String path, String upTo, List<?> first) {
    }

    static List<Bundle> declared(MigrationContext context) {
        Object value = context.playbook().setting("openrewrite.bundles");
        List<Bundle> bundles = new ArrayList<>();
        if (value instanceof Collection<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map) || map.get("recipe") == null
                        || (map.get("artifact") == null && !(map.get("first") instanceof List<?>))) {
                    throw new IllegalArgumentException("A recipe bundle needs 'recipe', and 'artifact' or a list 'first': " + item);
                }
                String path = map.get("path") == null ? "" : map.get("path").toString();
                bundles.add(new Bundle(map.get("recipe").toString(), map.get("artifact") == null ? null : map.get("artifact").toString(), path,
                        map.get("upTo") == null ? null : map.get("upTo").toString(),
                        map.get("first") instanceof List<?> first ? first : List.of()));
            }
        }
        return bundles;
    }

    /**
     * Writes the configuration file for the playbook's bundles, outside the workspace so that it never becomes
     * part of a stage's commit. Null when the playbook has no bundle. The caller deletes the file.
     */
    static Path configFile(MigrationContext context, Path buildRoot) throws IOException, InterruptedException {
        List<Bundle> bundles = declared(context);
        if (bundles.isEmpty()) {
            return null;
        }
        StringBuilder yaml = new StringBuilder();
        for (Bundle bundle : bundles) {
            yaml.append(configuration(bundle, bundle.artifact() == null ? null : jar(context, buildRoot, bundle.artifact())));
        }
        Path file = Files.createTempFile("renova-rewrite", ".yml");
        Files.writeString(file, yaml.toString());
        return file;
    }

    /** The bundle's files in release order, followed by the recipe that runs every recipe they define. */
    static String configuration(Bundle bundle, Path jar) throws IOException {
        StringBuilder yaml = new StringBuilder();
        List<String> names = new ArrayList<>();
        if (jar != null) try (ZipFile zip = new ZipFile(jar.toFile())) {
            List<? extends ZipEntry> files = zip.stream()
                    .filter(e -> !e.isDirectory() && e.getName().startsWith(bundle.path())
                            && e.getName().indexOf('/', bundle.path().length()) < 0
                            && (e.getName().endsWith(".yaml") || e.getName().endsWith(".yml")))
                    .filter(e -> bundle.upTo() == null || !Versions.isBelow(bundle.upTo(), release(e.getName(), bundle.path())))
                    .sorted(Comparator.comparing((ZipEntry e) -> release(e.getName(), bundle.path()), Versions::compare)
                            .thenComparing(ZipEntry::getName))
                    .toList();
            if (files.isEmpty()) {
                throw new IOException("No recipe file under " + bundle.path() + " in " + jar.getFileName());
            }
            for (ZipEntry file : files) {
                String text = new String(zip.getInputStream(file).readAllBytes(), StandardCharsets.UTF_8);
                Matcher name = NAME.matcher(text);
                while (name.find()) {
                    if (!names.contains(name.group(1))) {
                        names.add(name.group(1));
                    }
                }
                yaml.append(text.strip().startsWith("---") ? "" : "---\n").append(text.strip()).append('\n');
            }
        }
        yaml.append("---\ntype: specs.openrewrite.org/v1beta/recipe\nname: ").append(bundle.recipe())
                .append("\ndisplayName: ").append(bundle.recipe()).append("\ndescription: ")
                .append(bundle.artifact() == null ? "Recipes the playbook composes."
                        : "The recipes of " + bundle.artifact().replace(':', ' ') + " in release order.").append("\nrecipeList:\n");
        for (Object first : bundle.first()) {
            if (first instanceof Map<?, ?> map && map.size() == 1 && map.values().iterator().next() instanceof Map<?, ?> params) {
                yaml.append("  - ").append(map.keySet().iterator().next()).append(":\n");
                params.forEach((k, v) -> yaml.append("      ").append(k).append(": ")
                        .append(v instanceof Boolean || v instanceof Number ? v.toString() : "\"" + v + "\"").append('\n'));
            } else {
                yaml.append("  - ").append(first).append('\n');
            }
        }
        names.forEach(n -> yaml.append("  - ").append(n).append('\n'));
        return yaml.toString();
    }

    /** "quarkus-updates/core/3.10.alpha1.yaml" → "3.10". */
    private static String release(String entry, String path) {
        Matcher m = RELEASE.matcher(entry.substring(path.length()));
        return m.find() ? m.group(1) : "0";
    }

    /** The artifact's jar: from the local Maven repository, fetched by Maven when it is not there yet. */
    private static Path jar(MigrationContext context, Path buildRoot, String artifact) throws IOException, InterruptedException {
        String[] gav = artifact.split(":");
        if (gav.length != 3) {
            throw new IllegalArgumentException("A recipe bundle's artifact is group:artifact:version, not " + artifact);
        }
        String name = gav[1] + "-" + gav[2] + ".jar";
        Path local = Path.of(System.getProperty("user.home"), ".m2", "repository").resolve(gav[0].replace('.', '/'))
                .resolve(gav[1]).resolve(gav[2]).resolve(name);
        if (Files.isRegularFile(local)) {
            return local;
        }
        Path folder = Files.createTempDirectory("renova-recipes");
        List<String> cmd = MavenSupport.baseCommand(context, buildRoot);
        cmd.add("org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy");
        cmd.add("-Dartifact=" + artifact);
        cmd.add("-DoutputDirectory=" + folder);
        Proc.Result result = Proc.run(cmd, folder, TIMEOUT, MavenSupport.environment(context));
        Path copied = folder.resolve(name);
        if (!result.ok() || !Files.isRegularFile(copied)) {
            throw new IOException("Could not fetch the recipes in " + artifact + ":\n" + result.tail(15));
        }
        return copied;
    }
}
