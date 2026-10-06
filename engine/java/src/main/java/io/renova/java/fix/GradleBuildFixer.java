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
 *   <li>{@code action: removeDependency, remove: [...]} only deletes.</li>
 * </ul>
 */
public final class GradleBuildFixer implements Fixer {

    public static final String STRATEGY = "gradle";

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
            if (!action.equals("addDependency") && !action.equals("removeDependency")) {
                throw new IllegalArgumentException("Rule '" + ruleId + "': unknown gradle action '" + action
                        + "'; use addDependency or removeDependency");
            }
            for (String file : step.files()) {
                if (!file.endsWith(".gradle") && !file.endsWith(".gradle.kts")) {
                    continue;
                }
                Path path = root.resolve(file);
                String before = Files.readString(path, StandardCharsets.UTF_8);
                String content = before;
                for (String artifact : params.strings("remove")) {
                    content = removeDependency(content, artifact);
                }
                if (action.equals("addDependency")) {
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
