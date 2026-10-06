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
            if (tag(own, "version") != null) {
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
                + inner + "<version>" + version + "</version>\n"
                + (scope == null || scope.equals("compile") ? "" : inner + "<scope>" + scope + "</scope>\n")
                + indent + "</dependency>\n";
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
