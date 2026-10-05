package io.renova.java.behaviour;

import io.renova.core.behaviour.Scenario;
import io.renova.core.model.Module;
import io.renova.core.model.ProjectModel;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds GET entry points of a Java web application from its code: Spring MVC handler methods (under
 * the DispatcherServlet's mapping in web.xml), other servlets mapped to exact paths in web.xml, and
 * JSP pages outside WEB-INF. Each Spring route is also requested with a trailing slash, which Spring 6
 * stopped matching by default.
 */
final class EndpointDiscovery {

    private static final Pattern SERVLET = Pattern.compile(
            "(?s)<servlet>.*?<servlet-name>\\s*(.*?)\\s*</servlet-name>.*?<servlet-class>\\s*(.*?)\\s*</servlet-class>.*?</servlet>");
    private static final Pattern MAPPING = Pattern.compile(
            "(?s)<servlet-mapping>.*?<servlet-name>\\s*(.*?)\\s*</servlet-name>(.*?)</servlet-mapping>");
    private static final Pattern URL_PATTERN = Pattern.compile("<url-pattern>\\s*(.*?)\\s*</url-pattern>");
    private static final Pattern CONTROLLER = Pattern.compile("@(Rest)?Controller\\b");
    private static final Pattern MAPPING_ANNOTATION = Pattern.compile("@(RequestMapping|GetMapping)\\b\\s*(\\(((?:[^()]|\\([^()]*\\))*)\\))?");
    private static final Pattern CLASS_DECLARATION = Pattern.compile("\\bclass\\s+(\\w+)");
    private static final Pattern METHOD_NAME = Pattern.compile("\\s(\\w+)\\s*\\(");
    private static final Pattern STRING = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    /** {@code value = "..."} or {@code path = {"...", "..."}}; strings may contain braces ("/{id}"). */
    private static final Pattern NAMED_VALUE = Pattern.compile(
            "\\b(value|path)\\s*=\\s*(\\{\\s*\"(?:[^\"\\\\]|\\\\.)*\"(?:\\s*,\\s*\"(?:[^\"\\\\]|\\\\.)*\")*\\s*}|\"(?:[^\"\\\\]|\\\\.)*\")");
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{(\\w+)(?::[^}]*)?}");

    private EndpointDiscovery() {
    }

    static List<Scenario> discover(ProjectModel model, Path root, Module war) {
        Map<String, String> paths = new LinkedHashMap<>();
        paths.put("/", "the application root");
        Path webapp = moduleDir(root, war).resolve("src/main/webapp");
        String dispatcherPrefix = "";
        String dispatcherSuffix = "";
        Path webXml = webapp.resolve("WEB-INF/web.xml");
        if (Files.isRegularFile(webXml)) {
            String xml = read(webXml);
            Map<String, String> classes = new LinkedHashMap<>();
            Matcher s = SERVLET.matcher(xml);
            while (s.find()) {
                classes.put(s.group(1), s.group(2));
            }
            Matcher m = MAPPING.matcher(xml);
            while (m.find()) {
                String servlet = m.group(1);
                Matcher u = URL_PATTERN.matcher(m.group(2));
                while (u.find()) {
                    String pattern = u.group(1);
                    if (classes.getOrDefault(servlet, "").endsWith("DispatcherServlet")) {
                        if (pattern.startsWith("*.")) {
                            dispatcherSuffix = pattern.substring(1);
                        } else if (pattern.endsWith("/*") && pattern.length() > 2) {
                            dispatcherPrefix = pattern.substring(0, pattern.length() - 2);
                        }
                    } else if (pattern.startsWith("/") && !pattern.contains("*")) {
                        paths.putIfAbsent(pattern, "web.xml servlet " + servlet);
                    }
                }
            }
        }
        for (Module module : model.modules()) {
            Path sources = moduleDir(root, module).resolve("src/main/java");
            for (Path source : files(sources, ".java")) {
                String code = read(source).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
                if (CONTROLLER.matcher(code).find()) {
                    springRoutes(code, dispatcherPrefix, dispatcherSuffix, paths);
                }
            }
        }
        for (Path jsp : files(webapp, ".jsp")) {
            String rel = webapp.relativize(jsp).toString().replace('\\', '/');
            if (!rel.startsWith("WEB-INF/") && !rel.startsWith("META-INF/")) {
                paths.putIfAbsent("/" + rel, "JSP page");
            }
        }
        List<Scenario> scenarios = new ArrayList<>();
        paths.forEach((path, why) -> scenarios.add(new Scenario("s" + (scenarios.size() + 1), "GET", path, why)));
        return scenarios;
    }

    private static void springRoutes(String code, String prefix, String suffix, Map<String, String> paths) {
        Matcher type = CLASS_DECLARATION.matcher(code);
        if (!type.find()) {
            return;
        }
        String className = type.group(1);
        List<String> base = List.of("");
        Matcher annotation = MAPPING_ANNOTATION.matcher(code);
        while (annotation.find()) {
            String args = annotation.group(3) == null ? "" : annotation.group(3);
            List<String> values = values(args);
            if (annotation.start() < type.start()) {
                base = values.isEmpty() ? List.of("") : values;
                continue;
            }
            if (annotation.group(1).equals("RequestMapping") && args.contains("RequestMethod.")
                    && !args.contains("RequestMethod.GET")) {
                continue;
            }
            Matcher method = METHOD_NAME.matcher(code.substring(annotation.end(), Math.min(code.length(), annotation.end() + 400)));
            String handler = className + (method.find() ? "#" + method.group(1) : "");
            for (String b : base) {
                for (String v : values.isEmpty() ? List.of("") : values) {
                    String path = normalise(prefix + "/" + b + "/" + v);
                    String sample = PATH_VARIABLE.matcher(path).replaceAll(r -> sampleValue(r.group(1)));
                    String why = handler + " (Spring @" + annotation.group(1) + ")";
                    paths.putIfAbsent(sample + suffix, why);
                    if (suffix.isEmpty() && !sample.endsWith("/")) {
                        paths.putIfAbsent(sample + "/", why + ", with a trailing slash");
                    }
                }
            }
        }
    }

    /** The path strings of a mapping annotation: its value or path, or the default attribute. */
    static List<String> values(String args) {
        String source = args;
        Matcher named = NAMED_VALUE.matcher(args);
        if (named.find()) {
            source = named.group(2);
        } else if (args.contains("=")) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        Matcher s = STRING.matcher(source);
        while (s.find()) {
            values.add(s.group(1));
        }
        return values;
    }

    private static String sampleValue(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).matches(".*(id|number|no|count|page|year)$") ? "1" : "sample";
    }

    private static String normalise(String path) {
        String p = path.replaceAll("/+", "/");
        return p.length() > 1 && p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }

    private static Path moduleDir(Path root, Module module) {
        return module.path().equals(".") ? root : root.resolve(module.path());
    }

    private static List<Path> files(Path dir, String extension) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(f -> f.toString().endsWith(extension)).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
