package io.renova.java.detect;

import io.renova.core.engine.MigrationContext;
import io.renova.core.engine.PlanStep;
import io.renova.core.engine.StageResult;
import io.renova.core.model.Finding;
import io.renova.core.playbook.Rule;
import io.renova.core.spi.Detector;
import io.renova.core.spi.DetectorFactory;
import io.renova.core.spi.Fixer;

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

/**
 * {@code type: renamedMethodCallers, renames: {setSession: withSession, ...}} — calls to a method under its old
 * name on a project class that now declares it under the new one. An upgrade recipe that replaces an interface
 * renames the method where it is implemented; the code that calls it on the implementing class, most often a
 * test that sets the object up by hand, is left calling a method that no longer exists.
 *
 * <p>Fixed by {@code strategy: renameCalls}. Only calls on a variable declared with such a class in the same
 * file are found, so a method with the same old name on another type (a mock request's {@code setSession}) is
 * never touched.
 */
public final class RenamedMethodCallers implements DetectorFactory, Fixer {

    public static final String STRATEGY = "renameCalls";

    @Override
    public String type() {
        return "renamedMethodCallers";
    }

    @Override
    public String strategy() {
        return STRATEGY;
    }

    @Override
    public Detector create(Rule rule) {
        Map<String, String> renames = renames(rule);
        return ctx -> {
            Map<String, List<String>> sources = new LinkedHashMap<>();
            for (Path file : ctx.files("**/*.java")) {
                sources.put(io.renova.core.scan.ScanContext.toProjectPath(file), ctx.lines(file));
            }
            List<Finding> findings = new ArrayList<>();
            for (Call call : calls(sources, renames)) {
                findings.add(ctx.finding(rule, call.file(), call.line(), call.variable() + "." + call.oldName() + "(…) → " + call.newName(),
                        Map.of("variable", call.variable(), "old", call.oldName(), "new", call.newName())));
            }
            return findings;
        };
    }

    @Override
    public StageResult apply(MigrationContext context, List<PlanStep> steps) throws Exception {
        Path root = context.workspace().root();
        List<String> details = new ArrayList<>();
        int changed = 0;
        for (PlanStep step : steps) {
            for (Finding finding : step.findings()) {
                Path file = root.resolve(finding.file());
                List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
                String old = finding.data().get("old");
                if (finding.line() < 1 || finding.line() > lines.size() || old == null) {
                    continue;
                }
                String line = lines.get(finding.line() - 1);
                String renamed = call(finding.data().get("variable"), old).matcher(line)
                        .replaceAll("$1" + Matcher.quoteReplacement(finding.data().get("new")) + "$2");
                if (!renamed.equals(line)) {
                    lines.set(finding.line() - 1, renamed);
                    Files.write(file, lines, StandardCharsets.UTF_8);
                    details.add(finding.file() + ":" + finding.line() + " " + finding.evidence());
                    changed++;
                }
            }
        }
        return new StageResult(STRATEGY, StageResult.Status.APPLIED, changed + " call(s) renamed", details);
    }

    record Call(String file, int line, String variable, String oldName, String newName) {
    }

    private static Map<String, String> renames(Rule rule) {
        Object raw = rule.detect().get("renames");
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            throw new IllegalArgumentException("Rule '" + rule.id() + "': renamedMethodCallers needs 'renames: {old: new}'");
        }
        Map<String, String> renames = new LinkedHashMap<>();
        map.forEach((k, v) -> renames.put(k.toString(), v.toString()));
        return renames;
    }

    private static Pattern call(String variable, String method) {
        return Pattern.compile("(\\b" + Pattern.quote(variable) + "\\s*\\.\\s*)" + Pattern.quote(method) + "(\\s*\\()");
    }

    private static Pattern declares(String method) {
        return Pattern.compile("^\\s*(?:public|protected|private)?\\s*(?:static\\s+|final\\s+|synchronized\\s+)*[\\w<>\\[\\],.? ]+\\s+"
                + Pattern.quote(method) + "\\s*\\(");
    }

    static List<Call> calls(Map<String, List<String>> sources, Map<String, String> renames) {
        // Classes that declare a method under its new name and no longer under the old one.
        Map<String, Set<String>> renamedIn = new LinkedHashMap<>();
        Map<String, String> parents = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> source : sources.entrySet()) {
            String type = source.getKey().substring(source.getKey().lastIndexOf('/') + 1).replaceFirst("\\.java$", "");
            for (String line : source.getValue()) {
                Matcher parent = Pattern.compile("\\bclass\\s+" + Pattern.quote(type) + "\\b[^{]*?\\bextends\\s+(\\w+)").matcher(line);
                if (parent.find()) {
                    parents.put(type, parent.group(1));
                }
            }
            for (Map.Entry<String, String> rename : renames.entrySet()) {
                boolean newName = source.getValue().stream().anyMatch(l -> declares(rename.getValue()).matcher(l).find());
                boolean oldName = source.getValue().stream().anyMatch(l -> declares(rename.getKey()).matcher(l).find());
                if (newName && !oldName) {
                    renamedIn.computeIfAbsent(type, k -> new LinkedHashSet<>()).add(rename.getKey());
                }
            }
        }
        // A subclass inherits the renamed method.
        for (int round = 0; round < 5; round++) {
            for (Map.Entry<String, String> parent : parents.entrySet()) {
                Set<String> inherited = renamedIn.get(parent.getValue());
                if (inherited != null) {
                    renamedIn.computeIfAbsent(parent.getKey(), k -> new LinkedHashSet<>()).addAll(inherited);
                }
            }
        }
        List<Call> calls = new ArrayList<>();
        if (renamedIn.isEmpty()) {
            return calls;
        }
        for (Map.Entry<String, List<String>> source : sources.entrySet()) {
            String text = String.join("\n", source.getValue());
            Map<String, Set<String>> variables = new LinkedHashMap<>();
            for (Map.Entry<String, Set<String>> type : renamedIn.entrySet()) {
                String name = Pattern.quote(type.getKey());
                Matcher typed = Pattern.compile("\\b" + name + "(?:<[^>]*>)?\\s+(\\w+)\\s*(?:=|;|,|\\)|:)").matcher(text);
                while (typed.find()) {
                    variables.computeIfAbsent(typed.group(1), k -> new LinkedHashSet<>()).addAll(type.getValue());
                }
                Matcher inferred = Pattern.compile("\\bvar\\s+(\\w+)\\s*=\\s*new\\s+" + name + "\\b").matcher(text);
                while (inferred.find()) {
                    variables.computeIfAbsent(inferred.group(1), k -> new LinkedHashSet<>()).addAll(type.getValue());
                }
            }
            for (Map.Entry<String, Set<String>> variable : variables.entrySet()) {
                for (String old : variable.getValue()) {
                    Pattern call = call(variable.getKey(), old);
                    for (int i = 0; i < source.getValue().size(); i++) {
                        if (call.matcher(source.getValue().get(i)).find()) {
                            calls.add(new Call(source.getKey(), i + 1, variable.getKey(), old, renames.get(old)));
                        }
                    }
                }
            }
        }
        return calls;
    }
}
