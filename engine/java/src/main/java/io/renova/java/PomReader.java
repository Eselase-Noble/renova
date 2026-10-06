package io.renova.java;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the parts of a pom.xml the analysis needs, without resolving parents or the network. Values
 * are interpolated from the pom's own properties where possible; unresolved expressions are kept.
 */
public final class PomReader {

    private static final Pattern EXPRESSION = Pattern.compile("\\$\\{([^}]+)}");

    /**
     * @param managed    declared in dependencyManagement, so it sets defaults rather than adding the dependency
     * @param type       declared type, or null for the default (jar)
     * @param classifier declared classifier, or null
     */
    public record Dependency(String groupId, String artifactId, String version, String scope, boolean managed,
                             String type, String classifier) {
        public String coordinates() {
            return groupId + ":" + artifactId + (version == null ? "" : ":" + version);
        }

        /** What Maven requires to be unique within one dependency list: groupId:artifactId:type:classifier. */
        public String key() {
            return groupId + ":" + artifactId + ":" + (type == null ? "jar" : type) + ":" + (classifier == null ? "" : classifier);
        }
    }

    /** @param managed declared in pluginManagement */
    public record Plugin(String groupId, String artifactId, String version, boolean managed) {
    }

    /**
     * @param parent        groupId:artifactId of the declared parent, or null when there is none
     * @param parentVersion the declared parent's version, or null
     */
    public record Pom(String groupId, String artifactId, String version, String packaging, String javaVersion,
                      List<String> modules, List<Dependency> dependencies, List<Plugin> plugins,
                      Map<String, String> properties, String parent, String parentVersion) {
    }

    private PomReader() {
    }

    public static Pom read(Path file) throws IOException {
        Element project = parse(file).getDocumentElement();
        Map<String, String> props = new LinkedHashMap<>();
        Element properties = child(project, "properties");
        if (properties != null) {
            for (Element p : children(properties)) {
                props.put(p.getTagName(), p.getTextContent().strip());
            }
        }
        Element parent = child(project, "parent");
        String groupId = firstNonNull(text(project, "groupId"), parent == null ? null : text(parent, "groupId"));
        String version = firstNonNull(text(project, "version"), parent == null ? null : text(parent, "version"));
        String artifactId = text(project, "artifactId");
        props.putIfAbsent("project.groupId", groupId);
        props.putIfAbsent("project.artifactId", artifactId);
        props.putIfAbsent("project.version", version);
        props.values().removeIf(v -> v == null);

        List<Dependency> deps = new ArrayList<>();
        collectDependencies(child(project, "dependencies"), props, deps, false);
        Element management = child(project, "dependencyManagement");
        if (management != null) {
            collectDependencies(child(management, "dependencies"), props, deps, true);
        }
        List<Plugin> plugins = new ArrayList<>();
        Element build = child(project, "build");
        if (build != null) {
            collectPlugins(child(build, "plugins"), props, plugins, false);
            Element pluginManagement = child(build, "pluginManagement");
            if (pluginManagement != null) {
                collectPlugins(child(pluginManagement, "plugins"), props, plugins, true);
            }
        }

        List<String> modules = new ArrayList<>();
        Element modulesEl = child(project, "modules");
        if (modulesEl != null) {
            children(modulesEl).forEach(m -> modules.add(m.getTextContent().strip()));
        }

        return new Pom(interpolate(groupId, props), artifactId, interpolate(version, props),
                firstNonNull(text(project, "packaging"), "jar"), javaVersion(project, props),
                modules, deps, plugins, props, parent == null ? null : text(parent, "groupId") + ":" + text(parent, "artifactId"),
                parent == null ? null : interpolate(text(parent, "version"), props));
    }

    /** "1.8" → "8"; checks the usual properties, then the compiler plugin configuration. */
    static String javaVersion(Element project, Map<String, String> props) {
        String declared = null;
        for (String key : List.of("maven.compiler.release", "maven.compiler.target", "maven.compiler.source", "java.version")) {
            if (props.containsKey(key)) {
                declared = props.get(key);
                break;
            }
        }
        if (declared == null) {
            declared = compilerPluginSetting(project);
        }
        if (declared == null) {
            return null;
        }
        String value = interpolate(declared, props);
        return value.startsWith("1.") ? value.substring(2) : value;
    }

    private static String compilerPluginSetting(Element project) {
        Element build = child(project, "build");
        if (build == null) {
            return null;
        }
        List<Element> plugins = new ArrayList<>();
        Element pluginsEl = child(build, "plugins");
        if (pluginsEl != null) {
            plugins.addAll(children(pluginsEl));
        }
        Element management = child(build, "pluginManagement");
        if (management != null && child(management, "plugins") != null) {
            plugins.addAll(children(child(management, "plugins")));
        }
        for (Element plugin : plugins) {
            Element config = child(plugin, "configuration");
            if ("maven-compiler-plugin".equals(text(plugin, "artifactId")) && config != null) {
                return firstNonNull(text(config, "release"), firstNonNull(text(config, "target"), text(config, "source")));
            }
        }
        return null;
    }

    private static void collectDependencies(Element dependencies, Map<String, String> props, List<Dependency> out,
                                            boolean managed) {
        if (dependencies == null) {
            return;
        }
        for (Element d : children(dependencies)) {
            if (d.getTagName().equals("dependency")) {
                out.add(new Dependency(interpolate(text(d, "groupId"), props), interpolate(text(d, "artifactId"), props),
                        interpolate(text(d, "version"), props), text(d, "scope"), managed,
                        interpolate(text(d, "type"), props), interpolate(text(d, "classifier"), props)));
            }
        }
    }

    private static void collectPlugins(Element plugins, Map<String, String> props, List<Plugin> out, boolean managed) {
        if (plugins == null) {
            return;
        }
        for (Element p : children(plugins)) {
            if (p.getTagName().equals("plugin")) {
                String groupId = text(p, "groupId");
                out.add(new Plugin(groupId == null ? "org.apache.maven.plugins" : interpolate(groupId, props),
                        interpolate(text(p, "artifactId"), props), interpolate(text(p, "version"), props), managed));
            }
        }
    }

    static String interpolate(String value, Map<String, String> props) {
        if (value == null) {
            return null;
        }
        String current = value;
        for (int depth = 0; depth < 5 && current.contains("${"); depth++) {
            Matcher m = EXPRESSION.matcher(current);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String replacement = props.getOrDefault(m.group(1), m.group());
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            m.appendTail(sb);
            if (sb.toString().equals(current)) {
                break;
            }
            current = sb.toString();
        }
        return current;
    }

    private static Document parse(Path file) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Build files come from customer repositories: never resolve external entities.
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(file.toFile());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Cannot parse " + file + ": " + e.getMessage(), e);
        }
    }

    private static Element child(Element parent, String name) {
        for (Element e : children(parent)) {
            if (e.getTagName().equals(name)) {
                return e;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i).getNodeType() == Node.ELEMENT_NODE) {
                result.add((Element) nodes.item(i));
            }
        }
        return result;
    }

    private static String text(Element parent, String name) {
        Element e = child(parent, name);
        return e == null ? null : e.getTextContent().strip();
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
}
