package io.renova.java;

import io.renova.core.model.Module;
import io.renova.core.model.ProjectModel;
import io.renova.core.spi.RelatedFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Structural retrieval for Java: which project files help an AI understand a file. For a Java
 * source, the project types it extends or implements, then the project types it imports, then the
 * configuration files (Spring XML, web.xml, JSP, properties) that name it. For any other file, the
 * project types it names by fully qualified name, such as the classes of beans in a Spring XML file.
 */
final class JavaReferences {

    /** Enough to cover a type's direct neighbourhood without crowding the request. */
    static final int MAX_RESULTS = 8;

    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+([\\w.]+?)(\\.\\*)?\\s*;");
    private static final Pattern TYPE_HEADER = Pattern.compile("\\b(?:class|interface|enum|record)\\s+\\w+([^{]*)\\{");
    private static final Pattern SUPERTYPES = Pattern.compile("\\b(?:extends|implements)\\s+([\\w.,\\s]+)");
    private static final Pattern QUALIFIED_NAME = Pattern.compile("\\b((?:[a-z_][\\w]*\\.)+[A-Z][\\w]*)\\b");
    private static final List<String> CONFIG_EXTENSIONS = List.of(".xml", ".jsp", ".jspf", ".tag", ".tld", ".properties",
            ".yml", ".yaml");

    private JavaReferences() {
    }

    static List<RelatedFile> find(ProjectModel model, Path root, String file) {
        try {
            Path path = root.resolve(file).normalize();
            if (!path.startsWith(root) || !Files.isRegularFile(path)) {
                return List.of();
            }
            Map<String, String> types = typeIndex(model, root);
            String content = Files.readString(path, StandardCharsets.UTF_8);
            Map<String, String> found = new LinkedHashMap<>();
            if (file.endsWith(".java")) {
                javaReferences(types, content, found);
                String fqn = fqnOf(types, file);
                if (fqn != null) {
                    for (String config : configFiles(model, root)) {
                        if (!config.equals(file) && Files.readString(root.resolve(config), StandardCharsets.ISO_8859_1).contains(fqn)) {
                            found.putIfAbsent(config, "refers to " + fqn);
                        }
                    }
                }
            } else {
                Matcher m = QUALIFIED_NAME.matcher(content);
                while (m.find()) {
                    String source = types.get(m.group(1));
                    if (source != null) {
                        found.putIfAbsent(source, "project type " + m.group(1) + ", named in " + file);
                    }
                }
            }
            found.remove(file);
            List<RelatedFile> result = new ArrayList<>();
            found.forEach((p, why) -> result.add(new RelatedFile(p, false, why)));
            return result.size() > MAX_RESULTS ? result.subList(0, MAX_RESULTS) : result;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Supertypes first, then imported types, resolved to project sources. */
    private static void javaReferences(Map<String, String> types, String content, Map<String, String> found) {
        String code = content.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
        Matcher pkg = PACKAGE.matcher(code);
        String packageName = pkg.find() ? pkg.group(1) : "";
        Map<String, String> imported = new LinkedHashMap<>();
        List<String> wildcards = new ArrayList<>();
        Matcher imp = IMPORT.matcher(code);
        while (imp.find()) {
            if (imp.group(2) != null) {
                wildcards.add(imp.group(1));
            } else {
                imported.put(imp.group(1).substring(imp.group(1).lastIndexOf('.') + 1), imp.group(1));
            }
        }
        Matcher header = TYPE_HEADER.matcher(code);
        if (header.find()) {
            String clause = header.group(1);
            String previous;
            do {
                previous = clause;
                clause = clause.replaceAll("<[^<>]*>", "");
            } while (!clause.equals(previous));
            Matcher supers = SUPERTYPES.matcher(clause);
            while (supers.find()) {
                for (String name : supers.group(1).split("[,\\s]+")) {
                    if (name.isBlank() || name.equals("implements") || name.equals("extends")) {
                        continue;
                    }
                    String fqn = resolve(types, name, imported, wildcards, packageName);
                    if (fqn != null) {
                        found.putIfAbsent(types.get(fqn), "supertype " + fqn);
                    }
                }
            }
        }
        for (String fqn : imported.values()) {
            String source = types.get(fqn);
            if (source != null) {
                found.putIfAbsent(source, "project type " + fqn + ", imported here");
            }
        }
    }

    private static String resolve(Map<String, String> types, String name, Map<String, String> imported,
                                  List<String> wildcards, String packageName) {
        if (name.contains(".")) {
            return types.containsKey(name) ? name : null;
        }
        if (imported.containsKey(name)) {
            String fqn = imported.get(name);
            return types.containsKey(fqn) ? fqn : null;
        }
        String samePackage = packageName.isEmpty() ? name : packageName + "." + name;
        if (types.containsKey(samePackage)) {
            return samePackage;
        }
        for (String wildcard : wildcards) {
            if (types.containsKey(wildcard + "." + name)) {
                return wildcard + "." + name;
            }
        }
        return null;
    }

    private static String fqnOf(Map<String, String> types, String file) {
        return types.entrySet().stream().filter(e -> e.getValue().equals(file)).map(Map.Entry::getKey).findFirst().orElse(null);
    }

    /** Fully qualified name of every top-level type in the modules' main and test sources, to its path. */
    private static Map<String, String> typeIndex(ProjectModel model, Path root) throws IOException {
        Map<String, String> types = new LinkedHashMap<>();
        for (Module module : model.modules()) {
            for (String sources : List.of("src/main/java", "src/test/java")) {
                Path dir = moduleDir(root, module).resolve(sources);
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(dir)) {
                    for (Path source : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                        String relative = dir.relativize(source).toString().replace('\\', '/');
                        String fqn = relative.substring(0, relative.length() - ".java".length()).replace('/', '.');
                        types.putIfAbsent(fqn, root.relativize(source).toString().replace('\\', '/'));
                    }
                }
            }
        }
        return types;
    }

    /** Configuration files under the modules' src/main directories (outside the Java sources). */
    private static Set<String> configFiles(ProjectModel model, Path root) throws IOException {
        Set<String> result = new LinkedHashSet<>();
        for (Module module : model.modules()) {
            Path main = moduleDir(root, module).resolve("src/main");
            if (!Files.isDirectory(main)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(main)) {
                files.filter(Files::isRegularFile)
                        .filter(f -> !f.startsWith(main.resolve("java")))
                        .filter(f -> CONFIG_EXTENSIONS.stream().anyMatch(ext -> f.getFileName().toString().endsWith(ext)))
                        .sorted()
                        .forEach(f -> result.add(root.relativize(f).toString().replace('\\', '/')));
            }
        }
        return result;
    }

    private static Path moduleDir(Path root, Module module) {
        return module.path().equals(".") ? root : root.resolve(module.path());
    }
}
