package io.renova.java.fix;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small, targeted edits to pom.xml text. Works on the text rather than a parsed document so that
 * formatting, comments and element order are preserved exactly; only the edited lines change.
 */
final class PomEditor {

    /** The edited content and how many places changed (0 means unchanged). */
    record Result(String content, int changes) {
    }

    private record Span(int start, int end) {
        boolean contains(int index) {
            return index >= start && index < end;
        }
    }

    private PomEditor() {
    }

    /**
     * Sets the scope of every real dependency (not in dependencyManagement or a plugin) whose
     * groupId and artifactId match.
     */
    static Result setDependencyScope(String pom, BiPredicate<String, String> matches, String scope) {
        List<Span> excluded = spans(pom, "dependencyManagement");
        excluded.addAll(spans(pom, "plugin"));
        StringBuilder out = new StringBuilder();
        int last = 0;
        int changes = 0;
        Matcher block = Pattern.compile("(?s)<dependency>.*?</dependency>").matcher(pom);
        while (block.find()) {
            if (inside(excluded, block.start())) {
                continue;
            }
            String text = block.group();
            String own = text.replaceAll("(?s)<exclusions>.*?</exclusions>", "");
            String groupId = tag(own, "groupId");
            String artifactId = tag(own, "artifactId");
            if (groupId == null || artifactId == null || !matches.test(groupId, artifactId)) {
                continue;
            }
            String current = tag(own, "scope");
            String edited;
            if (current == null) {
                String anchor = own.contains("</version>") ? "</version>" : "</artifactId>";
                int at = text.indexOf(anchor) + anchor.length();
                edited = text.substring(0, at) + "\n" + indentOf(text, "<artifactId>") + "<scope>" + scope + "</scope>"
                        + text.substring(at);
            } else if (!current.equals(scope)) {
                edited = text.replaceFirst("<scope>\\s*" + Pattern.quote(current) + "\\s*</scope>", "<scope>" + scope + "</scope>");
            } else {
                continue;
            }
            out.append(pom, last, block.start()).append(edited);
            last = block.end();
            changes++;
        }
        out.append(pom.substring(last));
        return new Result(out.toString(), changes);
    }

    /**
     * Adds a version to every real dependency with this groupId and artifactId that declares none.
     * Declared versions are left alone.
     */
    static Result setDependencyVersion(String pom, String groupId, String artifactId, String version) {
        List<Span> excluded = realDependencyExclusions(pom);
        StringBuilder out = new StringBuilder();
        int last = 0;
        int changes = 0;
        Matcher block = Pattern.compile("(?s)<dependency>.*?</dependency>").matcher(pom);
        while (block.find()) {
            String text = block.group();
            String own = text.replaceAll("(?s)<exclusions>.*?</exclusions>", "");
            if (inside(excluded, block.start()) || !groupId.equals(tag(own, "groupId"))
                    || !artifactId.equals(tag(own, "artifactId")) || tag(own, "version") != null) {
                continue;
            }
            int at = text.indexOf("</artifactId>") + "</artifactId>".length();
            out.append(pom, last, block.start()).append(text, 0, at).append("\n").append(indentOf(text, "<artifactId>"))
                    .append("<version>").append(version).append("</version>").append(text.substring(at));
            last = block.end();
            changes++;
        }
        out.append(pom.substring(last));
        return new Result(out.toString(), changes);
    }

    /**
     * Sets the version of every dependency with this groupId and artifactId, real or managed: a version written
     * in place is replaced, one given by a property changes that property, and a dependency without a version
     * (managed elsewhere) is left alone.
     */
    static Result changeDependencyVersion(String pom, String groupId, String artifactId, String version) {
        String content = pom;
        int changes = 0;
        Matcher block = Pattern.compile("(?s)<dependency>.*?</dependency>").matcher(pom);
        List<String> properties = new ArrayList<>();
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (block.find()) {
            String text = block.group();
            String own = text.replaceAll("(?s)<exclusions>.*?</exclusions>", "");
            String declared = tag(own, "version");
            if (!groupId.equals(tag(own, "groupId")) || !artifactId.equals(tag(own, "artifactId")) || declared == null
                    || declared.equals(version)) {
                continue;
            }
            Matcher property = Pattern.compile("^\\$\\{([^}]+)}$").matcher(declared);
            if (property.find()) {
                properties.add(property.group(1));
                continue;
            }
            Matcher at = Pattern.compile("<version>\\s*" + Pattern.quote(declared) + "\\s*</version>").matcher(text);
            if (at.find()) {
                out.append(pom, last, block.start()).append(text, 0, at.start()).append("<version>").append(version)
                        .append("</version>").append(text.substring(at.end()));
                last = block.end();
                changes++;
            }
        }
        if (changes > 0) {
            content = out.append(pom.substring(last)).toString();
        }
        for (String name : properties) {
            Matcher value = Pattern.compile("(<" + Pattern.quote(name) + ">)[^<]*(</" + Pattern.quote(name) + ">)").matcher(content);
            if (value.find() && !value.group().contains(">" + version + "<")) {
                content = content.substring(0, value.start()) + value.group(1) + version + value.group(2) + content.substring(value.end());
                changes++;
            }
        }
        return new Result(content, changes);
    }

    /**
     * Replaces every dependency whose groupId and artifactId match (real or managed, outside plugins)
     * with new coordinates. A declared version becomes {@code version}; a real dependency without one
     * gets it, because nothing manages the new artifact yet. Scope, exclusions and the rest are kept.
     */
    static Result replaceDependency(String pom, BiPredicate<String, String> matches, String groupId, String artifactId,
                                    String version) {
        List<Span> plugins = spans(pom, "plugin");
        List<Span> managed = spans(pom, "dependencyManagement");
        StringBuilder out = new StringBuilder();
        int last = 0;
        int changes = 0;
        Matcher block = Pattern.compile("(?s)<dependency>.*?</dependency>").matcher(pom);
        while (block.find()) {
            String text = block.group();
            String own = text.replaceAll("(?s)<exclusions>.*?</exclusions>", "");
            String g = tag(own, "groupId");
            String a = tag(own, "artifactId");
            if (inside(plugins, block.start()) || g == null || a == null || !matches.test(g, a)) {
                continue;
            }
            int exclusions = text.indexOf("<exclusions>");
            String head = exclusions < 0 ? text : text.substring(0, exclusions);
            String tail = exclusions < 0 ? "" : text.substring(exclusions);
            head = head.replaceFirst("<groupId>\\s*" + Pattern.quote(g) + "\\s*</groupId>", "<groupId>" + groupId + "</groupId>")
                    .replaceFirst("<artifactId>\\s*" + Pattern.quote(a) + "\\s*</artifactId>", "<artifactId>" + artifactId + "</artifactId>");
            if (version == null) {
                // The build's platform manages the new artifact: a version written for the old one would be wrong.
                head = head.replaceFirst("[ \\t]*<version>[^<]*</version>[ \\t]*\\r?\\n?", "");
            } else if (tag(own, "version") != null) {
                head = head.replaceFirst("<version>[^<]*</version>", "<version>" + version + "</version>");
            } else if (!inside(managed, block.start())) {
                int at = head.indexOf("</artifactId>") + "</artifactId>".length();
                head = head.substring(0, at) + "\n" + indentOf(head, "<artifactId>") + "<version>" + version + "</version>"
                        + head.substring(at);
            }
            out.append(pom, last, block.start()).append(head).append(tail);
            last = block.end();
            changes++;
        }
        out.append(pom.substring(last));
        return new Result(out.toString(), changes);
    }

    /**
     * Removes real dependencies declared again with the same groupId, artifactId, type and
     * classifier, keeping the first declaration of each.
     */
    static Result removeDuplicateDependencies(String pom) {
        List<Span> excluded = realDependencyExclusions(pom);
        Set<String> seen = new HashSet<>();
        StringBuilder out = new StringBuilder();
        int last = 0;
        int changes = 0;
        Matcher block = Pattern.compile("(?s)<dependency>.*?</dependency>").matcher(pom);
        while (block.find()) {
            if (inside(excluded, block.start())) {
                continue;
            }
            String own = block.group().replaceAll("(?s)<exclusions>.*?</exclusions>", "");
            String type = tag(own, "type");
            String classifier = tag(own, "classifier");
            String key = tag(own, "groupId") + ":" + tag(own, "artifactId") + ":" + (type == null ? "jar" : type) + ":"
                    + (classifier == null ? "" : classifier);
            if (seen.add(key)) {
                continue;
            }
            // Remove the whole lines the block occupies when it stands on its own lines.
            int start = block.start();
            int lineStart = pom.lastIndexOf('\n', start - 1) + 1;
            if (pom.substring(lineStart, start).isBlank()) {
                start = lineStart;
            }
            int end = block.end();
            int lineEnd = pom.indexOf('\n', end);
            if (lineEnd >= 0 && pom.substring(end, lineEnd).isBlank()) {
                end = lineEnd + 1;
            }
            out.append(pom, last, start);
            last = end;
            changes++;
        }
        out.append(pom.substring(last));
        return new Result(out.toString(), changes);
    }

    /** Whether the project itself (not dependencyManagement, plugins or profiles) declares this dependency. */
    static boolean hasRealDependency(String pom, String groupId, String artifactId) {
        List<Span> excluded = realDependencyExclusions(pom);
        Matcher block = Pattern.compile("(?s)<dependency>.*?</dependency>").matcher(pom);
        while (block.find()) {
            String own = block.group().replaceAll("(?s)<exclusions>.*?</exclusions>", "");
            if (!inside(excluded, block.start()) && groupId.equals(tag(own, "groupId")) && artifactId.equals(tag(own, "artifactId"))) {
                return true;
            }
        }
        return false;
    }

    /** Regions whose dependencies are not the project's own: dependencyManagement, plugins and profiles. */
    private static List<Span> realDependencyExclusions(String pom) {
        List<Span> excluded = spans(pom, "dependencyManagement");
        excluded.addAll(spans(pom, "plugin"));
        excluded.addAll(spans(pom, "profiles"));
        return excluded;
    }

    /**
     * Sets a build plugin's version, updating every declaration of it, or adding the plugin to
     * {@code <build><plugins>} when it is not declared.
     */
    static Result setPluginVersion(String pom, String groupId, String artifactId, String version) {
        StringBuilder out = new StringBuilder();
        int last = 0;
        int changes = 0;
        boolean declared = false;
        Matcher block = Pattern.compile("(?s)<plugin>.*?</plugin>").matcher(pom);
        while (block.find()) {
            String text = block.group();
            // The plugin's own coordinates come before its configuration, executions and dependencies.
            int headEnd = firstIndex(text, "<configuration>", "<executions>", "<dependencies>", "<extensions>");
            String head = text.substring(0, headEnd);
            String pluginGroup = tag(head, "groupId");
            if (!artifactId.equals(tag(head, "artifactId"))
                    || !(pluginGroup == null ? "org.apache.maven.plugins" : pluginGroup).equals(groupId)) {
                continue;
            }
            declared = true;
            String current = tag(head, "version");
            String newHead;
            if (current == null) {
                int at = head.indexOf("</artifactId>") + "</artifactId>".length();
                newHead = head.substring(0, at) + "\n" + indentOf(head, "<artifactId>") + "<version>" + version + "</version>"
                        + head.substring(at);
            } else if (!current.equals(version)) {
                newHead = head.replaceFirst("<version>\\s*" + Pattern.quote(current) + "\\s*</version>", "<version>" + version + "</version>");
            } else {
                continue;
            }
            out.append(pom, last, block.start()).append(newHead).append(text.substring(headEnd));
            last = block.end();
            changes++;
        }
        out.append(pom.substring(last));
        if (declared) {
            return new Result(out.toString(), changes);
        }
        return addPlugin(pom, groupId, artifactId, version);
    }

    /** Removes the project's real dependencies (not managed ones, not plugins') that match. */
    static Result removeDependencies(String pom, BiPredicate<String, String> matches) {
        List<Span> excluded = spans(pom, "dependencyManagement");
        excluded.addAll(spans(pom, "plugin"));
        StringBuilder out = new StringBuilder();
        int last = 0;
        int changes = 0;
        Matcher block = Pattern.compile("(?s)[ \\t]*<dependency>.*?</dependency>[ \\t]*\\r?\\n?").matcher(pom);
        while (block.find()) {
            String own = block.group().replaceAll("(?s)<exclusions>.*?</exclusions>", "");
            String g = tag(own, "groupId");
            String a = tag(own, "artifactId");
            if (inside(excluded, block.start()) || g == null || a == null || !matches.test(g, a)) {
                continue;
            }
            out.append(pom, last, block.start());
            last = block.end();
            changes++;
        }
        out.append(pom.substring(last));
        return new Result(out.toString(), changes);
    }

    /** Removes the version of the project's real dependencies that match, for a platform that manages it. */
    static Result removeDependencyVersions(String pom, BiPredicate<String, String> matches) {
        List<Span> excluded = spans(pom, "dependencyManagement");
        excluded.addAll(spans(pom, "plugin"));
        StringBuilder out = new StringBuilder();
        int last = 0;
        int changes = 0;
        Matcher block = Pattern.compile("(?s)<dependency>.*?</dependency>").matcher(pom);
        while (block.find()) {
            String text = block.group();
            int exclusions = text.indexOf("<exclusions>");
            String head = exclusions < 0 ? text : text.substring(0, exclusions);
            String g = tag(head, "groupId");
            String a = tag(head, "artifactId");
            if (inside(excluded, block.start()) || g == null || a == null || tag(head, "version") == null || !matches.test(g, a)) {
                continue;
            }
            out.append(pom, last, block.start()).append(head.replaceFirst("[ \\t]*<version>[^<]*</version>[ \\t]*\\r?\\n?", ""))
                    .append(exclusions < 0 ? "" : text.substring(exclusions));
            last = block.end();
            changes++;
        }
        out.append(pom.substring(last));
        return new Result(out.toString(), changes);
    }

    /** Imports a bill of materials into {@code <dependencyManagement>}, unless the pom already imports it. */
    static Result importBom(String pom, String groupId, String artifactId, String version) {
        if (Pattern.compile("(?s)<artifactId>\\s*" + Pattern.quote(artifactId) + "\\s*</artifactId>").matcher(pom).find()) {
            return new Result(pom, 0);
        }
        String unit = indentUnit(pom);
        String entry = unit.repeat(3) + "<dependency>\n" + unit.repeat(4) + "<groupId>" + groupId + "</groupId>\n" + unit.repeat(4)
                + "<artifactId>" + artifactId + "</artifactId>\n" + unit.repeat(4) + "<version>" + version + "</version>\n"
                + unit.repeat(4) + "<type>pom</type>\n" + unit.repeat(4) + "<scope>import</scope>\n" + unit.repeat(3)
                + "</dependency>\n";
        List<Span> profiles = spans(pom, "profiles");
        Span management = firstSpanOutside(pom, "dependencyManagement", profiles);
        if (management != null) {
            int open = pom.indexOf("<dependencies>", management.start());
            if (open >= 0 && open < management.end()) {
                int lineEnd = pom.indexOf('\n', open) + 1;
                // First, so that it does not override versions the project manages itself after it.
                return new Result(pom.substring(0, lineEnd) + entry + pom.substring(lineEnd), 1);
            }
        }
        String block = unit + "<dependencyManagement>\n" + unit.repeat(2) + "<dependencies>\n" + entry + unit.repeat(2)
                + "</dependencies>\n" + unit + "</dependencyManagement>\n\n";
        return insertBeforeFirst(pom, block, profiles, "<dependencies>", "<build>", "<profiles>", "</project>");
    }

    /** Sets {@code <packaging>}; a pom without the element (a jar) gets one only for another packaging. */
    static Result setPackaging(String pom, String packaging) {
        Matcher m = Pattern.compile("<packaging>\\s*([^<]*?)\\s*</packaging>").matcher(pom);
        if (m.find()) {
            return m.group(1).equals(packaging) ? new Result(pom, 0)
                    : new Result(pom.substring(0, m.start()) + "<packaging>" + packaging + "</packaging>" + pom.substring(m.end()), 1);
        }
        return new Result(pom, 0);
    }

    /** Adds a build plugin given as XML (indented for {@code <plugins>}), unless one with that artifactId is declared. */
    static Result addPluginXml(String pom, String artifactId, String xml) {
        if (pom.contains("<artifactId>" + artifactId + "</artifactId>")) {
            return new Result(pom, 0);
        }
        // Declare it the usual way, then give it the body.
        Result added = addPlugin(pom, "renova.placeholder", artifactId, "0");
        Matcher placeholder = Pattern.compile("(?s)[ \\t]*<plugin>\\s*<groupId>renova\\.placeholder</groupId>.*?</plugin>\\n").matcher(added.content());
        return placeholder.find() ? new Result(added.content().substring(0, placeholder.start()) + xml
                + added.content().substring(placeholder.end()), 1) : new Result(pom, 0);
    }

    /**
     * Adds a dependency to the project's {@code <dependencies>} (creating the section if needed),
     * unless a real dependency with the same groupId and artifactId is already declared.
     */
    static Result addDependency(String pom, String groupId, String artifactId, String version, String scope) {
        List<Span> excluded = spans(pom, "dependencyManagement");
        excluded.addAll(spans(pom, "plugin"));
        excluded.addAll(spans(pom, "profiles"));
        Matcher existing = Pattern.compile("(?s)<dependency>.*?</dependency>").matcher(pom);
        while (existing.find()) {
            String own = existing.group().replaceAll("(?s)<exclusions>.*?</exclusions>", "");
            if (!inside(excluded, existing.start()) && groupId.equals(tag(own, "groupId"))
                    && artifactId.equals(tag(own, "artifactId"))) {
                return new Result(pom, 0);
            }
        }
        String unit = indentUnit(pom);
        Span dependencies = firstSpanOutside(pom, "dependencies", excluded);
        if (dependencies != null) {
            int close = pom.lastIndexOf("</dependencies>", dependencies.end());
            Matcher sibling = Pattern.compile("\\n([ \\t]*)<dependency>").matcher(pom.substring(dependencies.start(), close));
            String indent = sibling.find() ? sibling.group(1) : unit.repeat(2);
            int lineStart = pom.lastIndexOf('\n', close) + 1;
            return new Result(pom.substring(0, lineStart) + dependencyXml(indent, unit, groupId, artifactId, version, scope)
                    + pom.substring(lineStart), 1);
        }
        String block = unit + "<dependencies>\n" + dependencyXml(unit.repeat(2), unit, groupId, artifactId, version, scope)
                + unit + "</dependencies>\n\n";
        return insertBeforeFirst(pom, block, spans(pom, "profiles"), "<build>", "<profiles>", "</project>");
    }

    private static String dependencyXml(String indent, String unit, String groupId, String artifactId, String version,
                                        String scope) {
        String inner = indent + unit;
        return indent + "<dependency>\n"
                + inner + "<groupId>" + groupId + "</groupId>\n"
                + inner + "<artifactId>" + artifactId + "</artifactId>\n"
                + (version == null ? "" : inner + "<version>" + version + "</version>\n")
                + (scope == null || scope.equals("compile") ? "" : inner + "<scope>" + scope + "</scope>\n")
                + indent + "</dependency>\n";
    }

    /**
     * Adds an annotation processor to the compiler plugin's {@code annotationProcessorPaths}, when the pom has
     * that list and the processor is not in it. A version is left out when the build's platform manages it.
     */
    static Result addAnnotationProcessorPath(String pom, String groupId, String artifactId, String version) {
        Matcher paths = Pattern.compile("(?s)<annotationProcessorPaths[^>]*>(.*?)</annotationProcessorPaths>").matcher(pom);
        if (!paths.find() || paths.group(1).contains("<artifactId>" + artifactId + "</artifactId>")) {
            return new Result(pom, 0);
        }
        Matcher sibling = Pattern.compile("\\n([ \\t]*)<path>").matcher(paths.group(1));
        String unit = indentUnit(pom);
        String indent = sibling.find() ? sibling.group(1) : indentOf(pom, "<annotationProcessorPaths") + unit;
        // Indent the new entry the way the entries beside it are indented.
        Matcher child = Pattern.compile("\\n([ \\t]*)<groupId>").matcher(paths.group(1));
        String inner = child.find() ? child.group(1) : indent + unit;
        String path = indent + "<path>\n" + inner + "<groupId>" + groupId + "</groupId>\n" + inner + "<artifactId>"
                + artifactId + "</artifactId>\n" + (version == null ? "" : inner + "<version>" + version + "</version>\n")
                + indent + "</path>\n";
        int lineStart = pom.lastIndexOf('\n', paths.end(1)) + 1;
        return new Result(pom.substring(0, lineStart) + path + pom.substring(lineStart), 1);
    }

    /** Sets the version of the declared parent; a pom without a parent, or already on that version, is left alone. */
    static Result setParentVersion(String pom, String version) {
        Matcher parent = Pattern.compile("(?s)<parent>.*?</parent>").matcher(pom);
        if (!parent.find()) {
            return new Result(pom, 0);
        }
        Matcher declared = Pattern.compile("<version>([^<]*)</version>").matcher(parent.group());
        if (!declared.find() || declared.group(1).strip().equals(version)) {
            return new Result(pom, 0);
        }
        int start = parent.start() + declared.start(1);
        int end = parent.start() + declared.end(1);
        return new Result(pom.substring(0, start) + version + pom.substring(end), 1);
    }

    /** Removes a dependency that build plugins declare for themselves; a plugin left with none loses the empty list. */
    static Result removePluginDependency(String pom, String artifactId) {
        StringBuilder out = new StringBuilder();
        int last = 0;
        int changes = 0;
        Matcher plugin = Pattern.compile("(?s)<plugin>.*?</plugin>").matcher(pom);
        while (plugin.find()) {
            String text = plugin.group();
            String without = text.replaceAll("(?s)[ \\t]*<dependency>(?:(?!</dependency>).)*?<artifactId>\\s*" + Pattern.quote(artifactId)
                    + "\\s*</artifactId>.*?</dependency>[ \\t]*\\r?\\n?", "");
            if (without.equals(text)) {
                continue;
            }
            without = without.replaceAll("(?s)[ \\t]*<dependencies>\\s*</dependencies>[ \\t]*\\r?\\n?", "");
            out.append(pom, last, plugin.start()).append(without);
            last = plugin.end();
            changes++;
        }
        out.append(pom.substring(last));
        return new Result(out.toString(), changes);
    }

    /** Removes a property from the pom's {@code <properties>}, with its line. */
    static Result removeProperty(String pom, String name) {
        Matcher m = Pattern.compile("(?m)^[ \\t]*<" + Pattern.quote(name) + ">[^<]*</" + Pattern.quote(name) + ">[ \\t]*\\r?\\n").matcher(pom);
        if (m.find()) {
            return new Result(pom.substring(0, m.start()) + pom.substring(m.end()), 1);
        }
        Matcher inline = Pattern.compile("<" + Pattern.quote(name) + ">[^<]*</" + Pattern.quote(name) + ">").matcher(pom);
        return inline.find() ? new Result(pom.substring(0, inline.start()) + pom.substring(inline.end()), 1) : new Result(pom, 0);
    }

    /** Gives an existing property a new value, wherever the pom sets it; a pom without it is left alone. */
    static Result changeProperty(String pom, String name, String value) {
        Matcher m = Pattern.compile("(<" + Pattern.quote(name) + ">)\\s*([^<]*?)\\s*(</" + Pattern.quote(name) + ">)").matcher(pom);
        StringBuilder out = new StringBuilder();
        int changes = 0;
        while (m.find()) {
            if (!m.group(2).equals(value)) {
                changes++;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + value + m.group(3)));
        }
        m.appendTail(out);
        return new Result(changes == 0 ? pom : out.toString(), changes);
    }

    /** Adds a project-level property unless it already exists. */
    static Result setProperty(String pom, String name, String value) {
        List<Span> profiles = spans(pom, "profiles");
        Matcher props = Pattern.compile("(?s)<properties>(.*?)</properties>").matcher(pom);
        while (props.find()) {
            if (inside(profiles, props.start())) {
                continue;
            }
            if (Pattern.compile("<" + Pattern.quote(name) + ">").matcher(props.group(1)).find()) {
                return new Result(pom, 0);
            }
            String body = props.group(1);
            Matcher lastLine = Pattern.compile("\\n([ \\t]*)<[^/][^>]*>[^\\n]*$").matcher(body.stripTrailing());
            String indent = lastLine.find() ? lastLine.group(1) : indentUnit(pom).repeat(2);
            int insertAt = props.start(1) + body.stripTrailing().length();
            String line = "\n" + indent + "<" + name + ">" + value + "</" + name + ">";
            return new Result(pom.substring(0, insertAt) + line + pom.substring(insertAt), 1);
        }
        String unit = indentUnit(pom);
        String block = unit + "<properties>\n" + unit.repeat(2) + "<" + name + ">" + value + "</" + name + ">\n"
                + unit + "</properties>\n\n";
        return insertBeforeFirst(pom, block, profiles, "<dependencyManagement>", "<dependencies>", "<build>", "</project>");
    }

    private static Result addPlugin(String pom, String groupId, String artifactId, String version) {
        String unit = indentUnit(pom);
        String plugin = unit.repeat(3) + "<plugin>\n"
                + unit.repeat(4) + "<groupId>" + groupId + "</groupId>\n"
                + unit.repeat(4) + "<artifactId>" + artifactId + "</artifactId>\n"
                + unit.repeat(4) + "<version>" + version + "</version>\n"
                + unit.repeat(3) + "</plugin>\n";
        List<Span> excluded = spans(pom, "pluginManagement");
        excluded.addAll(spans(pom, "profiles"));
        Span build = firstSpanOutside(pom, "build", spans(pom, "profiles"));
        if (build != null) {
            Span plugins = firstSpanOutside(pom.substring(0, build.end()), "plugins", excluded);
            if (plugins != null && plugins.start() > build.start()) {
                int close = pom.lastIndexOf("</plugins>", plugins.end());
                int lineStart = pom.lastIndexOf('\n', close) + 1;
                return new Result(pom.substring(0, lineStart) + plugin + pom.substring(lineStart), 1);
            }
            int close = pom.lastIndexOf("</build>", build.end());
            int lineStart = pom.lastIndexOf('\n', close) + 1;
            String block = unit.repeat(2) + "<plugins>\n" + plugin + unit.repeat(2) + "</plugins>\n";
            return new Result(pom.substring(0, lineStart) + block + pom.substring(lineStart), 1);
        }
        int close = pom.lastIndexOf("</project>");
        int lineStart = pom.lastIndexOf('\n', close) + 1;
        String block = "\n" + unit + "<build>\n" + unit.repeat(2) + "<plugins>\n" + plugin + unit.repeat(2) + "</plugins>\n"
                + unit + "</build>\n";
        return new Result(pom.substring(0, lineStart) + block + pom.substring(lineStart), 1);
    }

    private static Result insertBeforeFirst(String pom, String block, List<Span> excluded, String... tags) {
        int best = -1;
        for (String tag : tags) {
            int from = 0;
            int at;
            while ((at = pom.indexOf(tag, from)) >= 0 && inside(excluded, at)) {
                from = at + 1;
            }
            if (at >= 0 && (best < 0 || at < best)) {
                best = at;
            }
        }
        if (best < 0) {
            return new Result(pom, 0);
        }
        int lineStart = pom.lastIndexOf('\n', best) + 1;
        return new Result(pom.substring(0, lineStart) + block + pom.substring(lineStart), 1);
    }

    private static List<Span> spans(String text, String tag) {
        List<Span> spans = new ArrayList<>();
        Matcher m = Pattern.compile("(?s)<" + tag + ">.*?</" + tag + ">").matcher(text);
        while (m.find()) {
            spans.add(new Span(m.start(), m.end()));
        }
        return spans;
    }

    private static Span firstSpanOutside(String text, String tag, List<Span> excluded) {
        return spans(text, tag).stream().filter(s -> !inside(excluded, s.start())).findFirst().orElse(null);
    }

    private static boolean inside(List<Span> spans, int index) {
        return spans.stream().anyMatch(s -> s.contains(index));
    }

    private static String tag(String text, String name) {
        Matcher m = Pattern.compile("<" + name + ">\\s*([^<]*?)\\s*</" + name + ">").matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static int firstIndex(String text, String... needles) {
        int best = text.length();
        for (String n : needles) {
            int i = text.indexOf(n);
            if (i >= 0 && i < best) {
                best = i;
            }
        }
        return best;
    }

    /** Leading whitespace of the line containing {@code needle}. */
    private static String indentOf(String text, String needle) {
        int at = text.indexOf(needle);
        int lineStart = text.lastIndexOf('\n', at) + 1;
        String prefix = text.substring(lineStart, at);
        return prefix.isBlank() ? prefix : "";
    }

    /** The file's indentation step, from the first indented child of {@code <project>}. */
    private static String indentUnit(String pom) {
        Matcher m = Pattern.compile("\\n([ \\t]+)<(modelVersion|groupId|artifactId)>").matcher(pom);
        return m.find() ? m.group(1) : "    ";
    }
}
