package io.renova.java.behaviour;

import io.renova.core.behaviour.Route;
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
 * Finds the entry points of a Java web application from its code: Spring MVC handler methods (under
 * the DispatcherServlet's mapping in web.xml), other servlets mapped to exact paths in web.xml, and
 * JSP pages outside WEB-INF. GET entry points become scenarios; each Spring route is also requested with a
 * trailing slash, which Spring 6 stopped matching by default. Every route keeps the file that handles it,
 * and a difference in how URLs are matched is sent to the Spring configuration that sets matching up.
 */
final class EndpointDiscovery {

    /** Scenarios to send, every route with its handler, and the routing configuration file (or null). */
    record Found(List<Scenario> scenarios, List<Route> routes, String routingConfig) {
    }

    private record Entry(String why, String handler) {
    }

    private static final Pattern SERVLET = Pattern.compile(
            "(?s)<servlet>(.*?)</servlet>");
    private static final Pattern MAPPING = Pattern.compile(
            "(?s)<servlet-mapping>.*?<servlet-name>\\s*(.*?)\\s*</servlet-name>(.*?)</servlet-mapping>");
    private static final Pattern URL_PATTERN = Pattern.compile("<url-pattern>\\s*(.*?)\\s*</url-pattern>");
    private static final Pattern CONTROLLER = Pattern.compile("@(Rest)?Controller\\b");
    private static final Pattern MAPPING_ANNOTATION = Pattern.compile(
            "@(RequestMapping|GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)\\b\\s*(\\(((?:[^()]|\\([^()]*\\))*)\\))?");
    private static final Pattern REQUEST_METHOD = Pattern.compile("RequestMethod\\.(\\w+)");
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
        return find(model, root, war).scenarios();
    }

    static Found find(ProjectModel model, Path root, Module war) {
        Map<String, Entry> gets = new LinkedHashMap<>();
        List<Route> routes = new ArrayList<>();
        Path webapp = moduleDir(root, war).resolve("src/main/webapp");
        Path webXml = webapp.resolve("WEB-INF/web.xml");
        String dispatcherPrefix = "";
        String dispatcherSuffix = "";
        String routingConfig = null;
        Map<String, String> sources = sourceIndex(model, root);
        gets.put("/", null); // first; its handler is known once web.xml is read
        if (Files.isRegularFile(webXml)) {
            String xml = read(webXml);
            String webXmlPath = relative(root, webXml);
            routingConfig = webXmlPath;
            Map<String, String> classes = new LinkedHashMap<>();
            Matcher s = SERVLET.matcher(xml);
            while (s.find()) {
                String name = tag(s.group(1), "servlet-name");
                String type = tag(s.group(1), "servlet-class");
                if (name == null || type == null) {
                    continue;
                }
                classes.put(name, type);
                if (type.endsWith("DispatcherServlet")) {
                    routingConfig = dispatcherConfig(root, webapp, s.group(1), name, webXmlPath);
                }
            }
            Matcher m = MAPPING.matcher(xml);
            while (m.find()) {
                String servlet = m.group(1);
                Matcher u = URL_PATTERN.matcher(m.group(2));
                while (u.find()) {
                    String pattern = u.group(1);
                    String type = classes.getOrDefault(servlet, "");
                    if (type.endsWith("DispatcherServlet")) {
                        if (pattern.startsWith("*.")) {
                            dispatcherSuffix = pattern.substring(1);
                        } else if (pattern.endsWith("/*") && pattern.length() > 2) {
                            dispatcherPrefix = pattern.substring(0, pattern.length() - 2);
                        }
                    } else if (pattern.startsWith("/") && !pattern.contains("*")) {
                        String handler = sources.getOrDefault(type, webXmlPath);
                        routes.add(new Route(null, pattern, handler));
                        gets.putIfAbsent(pattern, new Entry("web.xml servlet " + servlet, handler));
                    }
                }
            }
        }
        if (gets.get("/") == null) {
            gets.put("/", new Entry("the application root", routingConfig));
        }
        for (Map.Entry<String, String> source : sources.entrySet()) {
            String code = read(root.resolve(source.getValue())).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
            if (CONTROLLER.matcher(code).find()) {
                springRoutes(code, source.getValue(), dispatcherPrefix, dispatcherSuffix, routingConfig, gets, routes);
            }
        }
        for (Path jsp : files(webapp, ".jsp")) {
            String rel = webapp.relativize(jsp).toString().replace('\\', '/');
            String handler = relative(root, jsp);
            if (!rel.startsWith("WEB-INF/") && !rel.startsWith("META-INF/")) {
                routes.add(new Route(null, "/" + rel, handler));
                gets.putIfAbsent("/" + rel, new Entry("JSP page", handler));
            }
        }
        List<Scenario> scenarios = new ArrayList<>();
        gets.forEach((path, entry) -> scenarios.add(new Scenario("s" + (scenarios.size() + 1), "GET", path, entry.why(),
                entry.handler())));
        return new Found(scenarios, routes, routingConfig);
    }

    private static void springRoutes(String code, String file, String prefix, String suffix, String routingConfig,
                                     Map<String, Entry> gets, List<Route> routes) {
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
            List<String> methods = methods(annotation.group(1), args);
            Matcher method = METHOD_NAME.matcher(code.substring(annotation.end(), Math.min(code.length(), annotation.end() + 400)));
            String handler = className + (method.find() ? "#" + method.group(1) : "");
            for (String b : base) {
                for (String v : values.isEmpty() ? List.of("") : values) {
                    String template = normalise(prefix + "/" + b + "/" + v) + suffix;
                    methods.forEach(m -> routes.add(new Route(m, template, file)));
                    if (methods.stream().noneMatch(m -> m == null || m.equals("GET"))) {
                        continue;
                    }
                    String sample = PATH_VARIABLE.matcher(template).replaceAll(r -> sampleValue(r.group(1)));
                    String why = handler + " (Spring @" + annotation.group(1) + ")";
                    gets.putIfAbsent(sample, new Entry(why, file));
                    if (suffix.isEmpty() && !sample.endsWith("/")) {
                        // Whether a trailing slash matches is decided by Spring's configuration, not the handler.
                        gets.putIfAbsent(sample + "/", new Entry(why + ", with a trailing slash",
                                routingConfig == null ? file : routingConfig));
                    }
                }
            }
        }
    }

    /** The HTTP methods a mapping accepts; a list holding null means any. */
    private static List<String> methods(String annotation, String args) {
        if (!annotation.equals("RequestMapping")) {
            return List.of(annotation.substring(0, annotation.length() - "Mapping".length()).toUpperCase(java.util.Locale.ROOT));
        }
        List<String> methods = new ArrayList<>();
        Matcher m = REQUEST_METHOD.matcher(args);
        while (m.find()) {
            methods.add(m.group(1));
        }
        return methods.isEmpty() ? java.util.Collections.singletonList(null) : methods;
    }

    /** The Spring XML that configures the DispatcherServlet: contextConfigLocation, else NAME-servlet.xml. */
    private static String dispatcherConfig(Path root, Path webapp, String servletXml, String name, String fallback) {
        Matcher param = Pattern.compile("(?s)<param-name>\\s*contextConfigLocation\\s*</param-name>\\s*<param-value>\\s*(.*?)\\s*</param-value>")
                .matcher(servletXml);
        List<String> candidates = new ArrayList<>();
        if (param.find()) {
            for (String location : param.group(1).split("[,\\s]+")) {
                if (!location.isBlank() && !location.startsWith("classpath")) {
                    candidates.add(location.startsWith("/") ? location.substring(1) : location);
                }
            }
        }
        candidates.add("WEB-INF/" + name + "-servlet.xml");
        for (String candidate : candidates) {
            Path file = webapp.resolve(candidate);
            if (Files.isRegularFile(file)) {
                return relative(root, file);
            }
        }
        return fallback;
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

    /** Fully qualified class name to project-relative path, for every module's main sources. */
    private static Map<String, String> sourceIndex(ProjectModel model, Path root) {
        Map<String, String> index = new LinkedHashMap<>();
        for (Module module : model.modules()) {
            Path sources = moduleDir(root, module).resolve("src/main/java");
            for (Path source : files(sources, ".java")) {
                String rel = sources.relativize(source).toString().replace('\\', '/');
                index.putIfAbsent(rel.substring(0, rel.length() - ".java".length()).replace('/', '.'), relative(root, source));
            }
        }
        return index;
    }

    private static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + ">\\s*(.*?)\\s*</" + name + ">", Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    private static String sampleValue(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).matches(".*(id|number|no|count|page|year)$") ? "1" : "sample";
    }

    private static String normalise(String path) {
        String p = path.replaceAll("/+", "/");
        return p.length() > 1 && p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
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
