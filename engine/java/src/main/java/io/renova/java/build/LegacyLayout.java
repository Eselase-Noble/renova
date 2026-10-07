package io.renova.java.build;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Where a Java project without a Maven or Gradle build keeps its things: an Ant project (build.xml), an IDE
 * project, or sources beside a folder of jars. Read from the Ant build when there is one, otherwise from the
 * folder names such projects use.
 *
 * @param name        the project's name: the Ant project name, else the folder name
 * @param ant         whether the project has a build.xml
 * @param sources     folders of production Java sources, relative to the project
 * @param tests       folders of test sources
 * @param webRoot     the folder holding WEB-INF, or null when the project is not a web application
 * @param jars        the libraries the project carries, relative to the project
 * @param javaVersion the Java level the build compiles for ("6", "8"), or null when it does not say
 * @param encoding    the source encoding the build names, or null
 */
public record LegacyLayout(String name, boolean ant, List<String> sources, List<String> tests, String webRoot,
                           List<String> jars, String javaVersion, String encoding) {

    private static final List<String> SOURCE_DIRS = List.of("src/main/java", "src/java", "JavaSource", "source", "java", "src");
    private static final List<String> TEST_DIRS = List.of("src/test/java", "test/java", "test/src", "tests/java", "JavaTest",
            "src/test", "test", "tests");
    private static final List<String> WEB_DIRS = List.of("src/main/webapp", "WebContent", "WebRoot", "webapp", "web", "war",
            "public_html");
    /** Build output and tooling: never sources, and jars in them are products, not libraries. */
    private static final Set<String> OUTPUT_DIRS = Set.of("build", "dist", "target", "out", "bin", "classes", "output",
            "node_modules", ".git", ".renova", ".idea", ".settings", ".gradle", "renova-libs");
    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([^}]+)}");

    public LegacyLayout {
        sources = List.copyOf(sources);
        tests = List.copyOf(tests);
        jars = List.copyOf(jars);
    }

    public boolean webApplication() {
        return webRoot != null;
    }

    /** The layout of the project at {@code root}; empty when it has no Java sources where such projects keep them. */
    public static Optional<LegacyLayout> read(Path root) throws IOException {
        Path buildXml = root.resolve("build.xml");
        boolean ant = Files.isRegularFile(buildXml);
        String name = root.toAbsolutePath().normalize().getFileName().toString();
        Set<String> sources = new LinkedHashSet<>();
        Set<String> tests = new LinkedHashSet<>();
        String javaVersion = null;
        String encoding = null;

        if (ant) {
            try {
                Element project = parse(buildXml).getDocumentElement();
                if (!project.getAttribute("name").isBlank()) {
                    name = project.getAttribute("name").strip();
                }
                Map<String, String> properties = properties(root, project);
                NodeList compilers = project.getElementsByTagName("javac");
                for (int i = 0; i < compilers.getLength(); i++) {
                    Element javac = (Element) compilers.item(i);
                    List<String> dirs = new ArrayList<>();
                    for (String dir : resolve(javac.getAttribute("srcdir"), properties).split("[:;]")) {
                        dirs.add(dir);
                    }
                    NodeList nested = javac.getElementsByTagName("src");
                    for (int j = 0; j < nested.getLength(); j++) {
                        dirs.add(resolve(((Element) nested.item(j)).getAttribute("path"), properties));
                    }
                    for (String dir : dirs) {
                        String relative = relative(root, dir);
                        if (relative != null && hasJava(root.resolve(relative))) {
                            (looksLikeTests(relative) ? tests : sources).add(relative);
                        }
                    }
                    String level = resolve(firstNonBlank(javac.getAttribute("release"), javac.getAttribute("target"),
                            javac.getAttribute("source")), properties);
                    if (javaVersion == null && level.matches("[0-9.]+")) {
                        javaVersion = level.startsWith("1.") ? level.substring(2) : level;
                    }
                    String declared = resolve(javac.getAttribute("encoding"), properties);
                    if (encoding == null && !declared.isBlank() && !declared.contains("${")) {
                        encoding = declared;
                    }
                }
            } catch (Exception e) {
                // A build file that cannot be read: fall back to the folder names.
            }
        }
        if (sources.isEmpty()) {
            for (String dir : SOURCE_DIRS) {
                if (hasJava(root.resolve(dir))) {
                    sources.add(dir);
                    break;
                }
            }
        }
        if (tests.isEmpty()) {
            for (String dir : TEST_DIRS) {
                if (!sources.contains(dir) && hasJava(root.resolve(dir))) {
                    tests.add(dir);
                    break;
                }
            }
        }
        if (sources.isEmpty()) {
            return Optional.empty();
        }
        String webRoot = WEB_DIRS.stream().filter(d -> Files.isDirectory(root.resolve(d).resolve("WEB-INF"))).findFirst().orElse(null);
        return Optional.of(new LegacyLayout(name, ant, List.copyOf(sources), List.copyOf(tests), webRoot, jars(root), javaVersion,
                encoding));
    }

    /**
     * The projects of a build made of several: folders below {@code root} that have a build.xml and Java
     * sources of their own, as the root build names them ({@code <ant dir>}, {@code <subant>}) or, where it
     * names none, the folders directly below it. Empty when the root is itself one project, or none.
     */
    public static List<String> modules(Path root) throws IOException {
        if (read(root).isPresent()) {
            return List.of();
        }
        Set<String> candidates = new LinkedHashSet<>();
        Path buildXml = root.resolve("build.xml");
        if (Files.isRegularFile(buildXml)) {
            try {
                Element project = parse(buildXml).getDocumentElement();
                Map<String, String> properties = properties(root, project);
                NodeList calls = project.getElementsByTagName("ant");
                for (int i = 0; i < calls.getLength(); i++) {
                    Element call = (Element) calls.item(i);
                    String dir = resolve(call.getAttribute("dir"), properties);
                    String file = resolve(call.getAttribute("antfile"), properties);
                    if (dir.isBlank() && file.contains("/")) {
                        dir = file.substring(0, file.lastIndexOf('/'));
                    }
                    candidates.add(dir);
                }
                NodeList several = project.getElementsByTagName("subant");
                for (int i = 0; i < several.getLength(); i++) {
                    Element call = (Element) several.item(i);
                    for (String dir : resolve(call.getAttribute("buildpath"), properties).split("[:;,]")) {
                        candidates.add(dir);
                    }
                    for (String list : new String[] {"filelist", "dirset", "fileset"}) {
                        NodeList lists = call.getElementsByTagName(list);
                        for (int j = 0; j < lists.getLength(); j++) {
                            Element e = (Element) lists.item(j);
                            String base = resolve(e.getAttribute("dir"), properties);
                            for (String name : resolve(firstNonBlank(e.getAttribute("files"), e.getAttribute("includes")), properties).split("[,\\s]+")) {
                                String dir = name.replaceAll("/?build\\.xml$", "");
                                if (!dir.contains("*")) {
                                    candidates.add(base.isBlank() || base.equals(".") ? dir : base + "/" + dir);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // A build file that cannot be read: fall back to the folders.
            }
        }
        List<String> modules = new ArrayList<>();
        for (String candidate : candidates) {
            String dir = relative(root, candidate);
            if (dir != null && !modules.contains(dir) && Files.isRegularFile(root.resolve(dir).resolve("build.xml"))
                    && read(root.resolve(dir)).isPresent()) {
                modules.add(dir);
            }
        }
        if (modules.isEmpty()) {
            try (Stream<Path> children = Files.list(root)) {
                for (Path child : children.filter(Files::isDirectory).sorted().toList()) {
                    String dir = child.getFileName().toString();
                    if (!OUTPUT_DIRS.contains(dir) && Files.isRegularFile(child.resolve("build.xml")) && read(child).isPresent()) {
                        modules.add(dir);
                    }
                }
            }
        }
        return modules;
    }

    /**
     * Which of the other projects this one's build names: a path anywhere in its build.xml or the property
     * files it loads that leads into their folder (the jar or classes of a project it compiles against).
     */
    public static List<String> uses(Path root, String module, List<String> modules) {
        Path base = root.toAbsolutePath().normalize();
        Path dir = base.resolve(module);
        List<String> used = new ArrayList<>();
        try {
            Element project = parse(dir.resolve("build.xml")).getDocumentElement();
            Map<String, String> properties = properties(dir, project);
            List<String> values = new ArrayList<>(properties.values());
            NodeList all = project.getElementsByTagName("*");
            for (int i = 0; i < all.getLength(); i++) {
                org.w3c.dom.NamedNodeMap attributes = all.item(i).getAttributes();
                for (int j = 0; j < attributes.getLength(); j++) {
                    values.add(resolve(attributes.item(j).getNodeValue(), properties));
                }
            }
            for (String value : values) {
                for (String part : value.split("[:;,]")) {
                    if (part.isBlank() || part.contains("${") || !part.contains("..")) {
                        continue;
                    }
                    Path target;
                    try {
                        target = dir.resolve(part.strip()).normalize();
                    } catch (java.nio.file.InvalidPathException e) {
                        continue;
                    }
                    for (String other : modules) {
                        if (!other.equals(module) && target.startsWith(base.resolve(other)) && !used.contains(other)) {
                            used.add(other);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Unreadable: no dependencies are known, and the compiler will name what is missing.
        }
        return used;
    }

    static List<String> jars(Path root) throws IOException {
        try (Stream<Path> files = Files.find(root, 7, (p, attrs) -> attrs.isRegularFile() && p.getFileName().toString().endsWith(".jar"))) {
            return files.map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .filter(p -> Stream.of(p.split("/")).noneMatch(OUTPUT_DIRS::contains))
                    // Ant's own task libraries are tools of the old build, not of the application.
                    .filter(p -> !p.matches("(?i)(.*/)?ant([-.][^/]*)?\\.jar"))
                    .sorted().toList();
        }
    }

    private static boolean looksLikeTests(String dir) {
        return Stream.of(dir.split("/")).anyMatch(part -> part.toLowerCase(java.util.Locale.ROOT).matches("tests?|.*test|test.*|it"));
    }

    private static boolean hasJava(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> files = Files.find(dir, 12, (p, attrs) -> attrs.isRegularFile() && p.toString().endsWith(".java"))) {
            return files.findAny().isPresent();
        }
    }

    /** {@code dir} as a path inside the project, or null when it is outside it or not resolved. */
    private static String relative(Path root, String dir) {
        if (dir.isBlank() || dir.contains("${")) {
            return null;
        }
        Path base = root.toAbsolutePath().normalize();
        Path resolved = base.resolve(dir).normalize();
        if (!resolved.startsWith(base) || resolved.equals(base)) {
            return null;
        }
        return base.relativize(resolved).toString().replace('\\', '/');
    }

    /** The build's properties, with the property files it loads; the first definition wins, as in Ant. */
    private static Map<String, String> properties(Path root, Element project) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("basedir", ".");
        properties.put("ant.project.name", project.getAttribute("name"));
        NodeList declared = project.getElementsByTagName("property");
        for (int i = 0; i < declared.getLength(); i++) {
            Element property = (Element) declared.item(i);
            if (!property.getAttribute("file").isBlank()) {
                Path file = root.resolve(resolve(property.getAttribute("file"), properties));
                if (Files.isRegularFile(file)) {
                    Properties loaded = new Properties();
                    try (InputStream in = Files.newInputStream(file)) {
                        loaded.load(in);
                    } catch (IOException | IllegalArgumentException e) {
                        continue;
                    }
                    loaded.stringPropertyNames().forEach(k -> properties.putIfAbsent(k, resolve(loaded.getProperty(k), properties)));
                }
            } else if (!property.getAttribute("name").isBlank()) {
                String value = firstNonBlank(property.getAttribute("value"), property.getAttribute("location"));
                properties.putIfAbsent(property.getAttribute("name"), resolve(value, properties));
            }
        }
        return properties;
    }

    private static String resolve(String text, Map<String, String> properties) {
        String result = text == null ? "" : text.strip();
        for (int round = 0; round < 5 && result.contains("${"); round++) {
            Matcher m = REFERENCE.matcher(result);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                String value = properties.get(m.group(1));
                m.appendReplacement(out, Matcher.quoteReplacement(value == null ? m.group() : value));
            }
            m.appendTail(out);
            if (out.toString().equals(result)) {
                break;
            }
            result = out.toString();
        }
        return result;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static Document parse(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setExpandEntityReferences(false);
        try (InputStream in = Files.newInputStream(file)) {
            return factory.newDocumentBuilder().parse(in);
        }
    }
}
