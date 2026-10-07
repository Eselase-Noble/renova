package io.renova.java.fix;

import io.renova.core.engine.MigrationContext;
import io.renova.core.engine.PlanStep;
import io.renova.core.engine.StageResult;
import io.renova.core.model.Finding;
import io.renova.core.playbook.Params;
import io.renova.core.spi.Fixer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Edits to Gradle build files that a playbook spells out, for what recipes leave behind in them. The build
 * file is treated as text, so its formatting and comments stay as they are.
 *
 * <ul>
 *   <li>{@code action: addDependency} adds the dependency each finding carries (from
 *       {@code gradleImportWithoutDependency}) to the build file's {@code dependencies} block;
 *       {@code remove: ["javax.xml.bind:jaxb-api"]} also deletes the lines that declare those artifacts.</li>
 *       With {@code dependency: "g:a[:v]"} (and {@code configuration}, default implementation) it adds that one
 *       instead, to every build file the rule found.</li>
 *   <li>{@code action: removeDependency, remove: [...]} only deletes.</li>
 *   <li>{@code action: setPluginVersion, plugins: ["org.jetbrains.kotlin."], version: "1.9.25"} sets the version
 *       of every plugin in a {@code plugins} block whose id starts with one of the prefixes.</li>
 *   <li>{@code action: setWrapperVersion, version: "9.1.0"} points gradle-wrapper.properties at that Gradle.</li>
 * </ul>
 */
public final class GradleBuildFixer implements Fixer {

    public static final String STRATEGY = "gradle";
    private static final List<String> ACTIONS = List.of("addDependency", "removeDependency", "setPluginVersion", "setWrapperVersion");
    /** A plugin with a literal version: group 1 is everything before the version, 2 the id, 3 a kotlin("…") name. */
    private static final Pattern PLUGIN = Pattern.compile(
            "((?:\\bid\\s*\\(?\\s*['\"]([\\w.-]+)['\"]\\s*\\)?|\\bkotlin\\s*\\(\\s*['\"]([\\w.-]+)['\"]\\s*\\))\\s*version\\s*\\(?\\s*['\"])[^'\"]+");

    @Override
    public String strategy() {
        return STRATEGY;
    }

    @Override
    public StageResult apply(MigrationContext context, List<PlanStep> steps) throws Exception {
        Path root = context.workspace().root();
        List<String> details = new ArrayList<>();
        int changed = 0;
        for (PlanStep step : steps) {
            String ruleId = step.rule().id();
            Params params = step.rule().fix().params(ruleId);
            String action = params.string("action");
            if (!ACTIONS.contains(action)) {
                throw new IllegalArgumentException("Rule '" + ruleId + "': unknown gradle action '" + action
                        + "'; use one of " + ACTIONS);
            }
            for (String file : step.files()) {
                if (action.equals("setWrapperVersion")) {
                    if (file.endsWith("gradle-wrapper.properties")) {
                        Path properties = root.resolve(file);
                        String before = Files.readString(properties, StandardCharsets.UTF_8);
                        String content = setWrapperVersion(before, params.string("version"));
                        if (!content.equals(before)) {
                            Files.writeString(properties, content, StandardCharsets.UTF_8);
                            changed++;
                        }
                        details.add(ruleId + ": " + file + ": " + (content.equals(before) ? "already correct" : action));
                    }
                    continue;
                }
                if (!file.endsWith(".gradle") && !file.endsWith(".gradle.kts")) {
                    continue;
                }
                Path path = root.resolve(file);
                String before = Files.readString(path, StandardCharsets.UTF_8);
                String content = before;
                for (String artifact : params.strings("remove")) {
                    content = removeDependency(content, artifact);
                }
                if (action.equals("setPluginVersion")) {
                    content = setPluginVersion(content, params.requiredStrings("plugins"), params.string("version"));
                }
                if (action.equals("addDependency") && params.optString("dependency").isPresent()) {
                    content = addDependency(content, params.optString("configuration").orElse("implementation"),
                            params.string("dependency"), file.endsWith(".kts"));
                } else if (action.equals("addDependency")) {
                    for (Finding f : step.findings()) {
                        if (f.file().equals(file) && f.data().containsKey("dependency")) {
                            content = addDependency(content, f.data().getOrDefault("configuration", "implementation"),
                                    f.data().get("dependency"), file.endsWith(".kts"));
                        }
                    }
                }
                if (!content.equals(before)) {
                    Files.writeString(path, content, StandardCharsets.UTF_8);
                    changed++;
                }
                details.add(ruleId + ": " + file + ": " + (content.equals(before) ? "already correct" : action));
            }
        }
        return new StageResult(STRATEGY, StageResult.Status.APPLIED, changed + " build file edit(s) by " + steps.size() + " rule(s)", details);
    }

    /**
     * Adds a line to the project's {@code dependencies} block (not the one inside {@code buildscript}), written
     * the way the block's other lines are; a build without the block gets one at the end.
     */
    static String addDependency(String build, String configuration, String dependency, boolean kotlin) {
        String[] parts = dependency.split(":");
        if (build.contains(parts[0] + ":" + parts[1])) {
            return build;
        }
        Matcher block = Pattern.compile("(?m)^([ \\t]*)dependencies\\s*\\{").matcher(build);
        while (block.find()) {
            if (insideBuildscript(build, block.start())) {
                continue;
            }
            int close = closingBrace(build, block.end());
            if (close < 0) {
                break;
            }
            String body = build.substring(block.end(), close);
            Matcher line = Pattern.compile("(?m)^([ \\t]+)\\w+[ (]+(['\"])").matcher(body);
            boolean styled = line.find();
            String indent = styled ? line.group(1) : block.group(1) + "    ";
            String quote = kotlin ? "\"" : styled ? line.group(2) : "'";
            String declaration = kotlin || (styled && body.substring(line.start(), line.end()).contains("("))
                    ? configuration + "(" + quote + dependency + quote + ")"
                    : configuration + " " + quote + dependency + quote;
            int at = build.lastIndexOf('\n', close - 1) + 1;
            return build.substring(0, at) + indent + declaration + "\n" + build.substring(at);
        }
        String quote = kotlin ? "\"" : "'";
        return build + (build.endsWith("\n") ? "" : "\n") + "\ndependencies {\n    " + configuration
                + (kotlin ? "(" + quote + dependency + quote + ")" : " " + quote + dependency + quote) + "\n}\n";
    }

    /**
     * Sets the version of the plugins whose id starts with one of the prefixes, where a version is written:
     * {@code id 'x' version '1'}, {@code id("x") version "1"}, and Kotlin's {@code kotlin("jvm") version "1"}.
     */
    static String setPluginVersion(String build, List<String> prefixes, String version) {
        Matcher m = PLUGIN.matcher(build);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String id = m.group(2) != null ? m.group(2) : "org.jetbrains.kotlin." + m.group(3);
            boolean wanted = prefixes.stream().anyMatch(id::startsWith);
            m.appendReplacement(out, Matcher.quoteReplacement(wanted ? m.group(1) + version : m.group()));
        }
        return m.appendTail(out).toString();
    }

    /** Points the wrapper at another Gradle; the old distribution's checksum goes with it. */
    static String setWrapperVersion(String properties, String version) {
        return properties.replaceAll("(?m)^(distributionUrl=.*/gradle-)[^-/]+(-(?:bin|all)\\.zip)\\s*$", "$1" + version + "$2")
                .replaceAll("(?m)^distributionSha256Sum=.*\\R?", "");
    }

    /** Deletes the lines that declare {@code groupId:artifactId}, with any version. */
    static String removeDependency(String build, String artifact) {
        return Pattern.compile("(?m)^[ \\t]*\\w+[ (]+['\"]" + Pattern.quote(artifact) + "(?::[^'\"]*)?['\"]\\)?[ \\t]*\\R?").matcher(build)
                .replaceAll("");
    }

    private static boolean insideBuildscript(String build, int index) {
        Matcher script = Pattern.compile("(?m)^[ \\t]*buildscript\\s*\\{").matcher(build);
        while (script.find()) {
            int close = closingBrace(build, script.end());
            if (index > script.start() && (close < 0 || index < close)) {
                return true;
            }
        }
        return false;
    }

    /** The index of the brace that closes the block opened just before {@code from}; -1 when it is not closed. */
    private static int closingBrace(String text, int from) {
        int depth = 1;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }
}
