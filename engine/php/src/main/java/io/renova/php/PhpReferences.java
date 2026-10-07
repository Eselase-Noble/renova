package io.renova.php;

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
 * The project's own classes that a PHP file uses: the ones it imports, extends, creates or calls statically.
 * A fix often depends on them (whether a method is static, what a constructor takes), and PHP has no compiler
 * to say so.
 */
final class PhpReferences {

    private static final int MAX_FILES = 4000;
    private static final int MAX_REFERENCES = 8;
    private static final Pattern NAMESPACE = Pattern.compile("(?m)^\\s*namespace\\s+([\\w\\\\]+)\\s*[;{]");
    private static final Pattern DECLARED = Pattern.compile("(?m)^\\s*(?:abstract\\s+|final\\s+|readonly\\s+)*(?:class|interface|trait|enum)\\s+(\\w+)");
    private static final Pattern USE = Pattern.compile("(?m)^\\s*use\\s+([\\w\\\\]+)(?:\\s+as\\s+(\\w+))?\\s*;");
    private static final Pattern USED = Pattern.compile("(?:\\bnew\\s+|\\bextends\\s+|\\bimplements\\s+|\\binstanceof\\s+|[(,\\s:?|])(\\\\?[A-Z][\\w\\\\]*)(?=\\s*(?:::|\\(|\\s+\\$|\\s*[{,;)]|\\s+(?:implements|extends)\\b))");

    private PhpReferences() {
    }

    static List<RelatedFile> of(Path root, String file) {
        Path source = root.resolve(file);
        if (!file.endsWith(".php") || !Files.isRegularFile(source)) {
            return List.of();
        }
        Map<String, String> classes = index(root);
        String code = read(source);
        Matcher ns = NAMESPACE.matcher(code);
        String namespace = ns.find() ? ns.group(1) + "\\" : "";
        Map<String, String> imports = new LinkedHashMap<>();
        Matcher use = USE.matcher(code);
        while (use.find()) {
            String fqcn = use.group(1).replaceFirst("^\\\\", "");
            imports.put(use.group(2) != null ? use.group(2) : fqcn.substring(fqcn.lastIndexOf('\\') + 1), fqcn);
        }
        Set<String> wanted = new LinkedHashSet<>(imports.values());
        Matcher used = USED.matcher(code);
        while (used.find()) {
            String name = used.group(1);
            if (name.startsWith("\\")) {
                wanted.add(name.substring(1));
            } else {
                String first = name.contains("\\") ? name.substring(0, name.indexOf('\\')) : name;
                wanted.add(imports.containsKey(first) ? imports.get(first) + name.substring(first.length()) : namespace + name);
            }
        }
        List<RelatedFile> references = new ArrayList<>();
        for (String fqcn : wanted) {
            String path = classes.get(fqcn);
            if (path != null && !path.equals(file) && references.stream().noneMatch(r -> r.path().equals(path))) {
                references.add(new RelatedFile(path, false, "declares " + fqcn + ", which this file uses"));
                if (references.size() == MAX_REFERENCES) {
                    break;
                }
            }
        }
        return references;
    }

    /** Every class, interface, trait and enum the project declares, by full name, to the file that declares it. */
    private static Map<String, String> index(Path root) {
        Map<String, String> classes = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".php") && Files.isRegularFile(f)
                    && !PhpPlugin.produced(root.relativize(f))).sorted().limit(MAX_FILES).toList()) {
                String code = read(file);
                Matcher ns = NAMESPACE.matcher(code);
                String namespace = ns.find() ? ns.group(1) + "\\" : "";
                Matcher declared = DECLARED.matcher(code);
                while (declared.find()) {
                    classes.putIfAbsent(namespace + declared.group(1), root.relativize(file).toString().replace('\\', '/'));
                }
            }
        } catch (IOException | UncheckedIOException e) {
            // What could be read is still worth having.
        }
        return classes;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            return "";
        }
    }
}
