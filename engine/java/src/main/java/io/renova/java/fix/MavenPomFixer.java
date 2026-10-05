package io.renova.java.fix;

import io.renova.core.engine.MigrationContext;
import io.renova.core.engine.PlanStep;
import io.renova.core.engine.StageResult;
import io.renova.core.model.Finding;
import io.renova.core.playbook.Params;
import io.renova.core.spi.Fixer;
import io.renova.java.detect.DependencyDetector;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Deterministic pom.xml edits driven by the playbook ({@code strategy: maven}):
 * <ul>
 *   <li>{@code action: setScope, scope: provided}: dependencies matching the rule's detect coordinates</li>
 *   <li>{@code action: setPluginVersion, plugin: maven-war-plugin, version: "3.4.0", groupId?}</li>
 *   <li>{@code action: setProperty, name: maven.compiler.target, value: "${maven.compiler.source}"}</li>
 *   <li>{@code action: addDependency}: adds the dependency each finding describes in its data
 *       (groupId, artifactId, version, scope), or the fixed {@code dependency: "g:a:v"} (and
 *       {@code scope}) given in the params</li>
 *   <li>{@code action: setVersion}: adds the version each finding gives in its data (groupId,
 *       artifactId, version) to that dependency, where it declares none</li>
 *   <li>{@code action: removeDuplicates}: removes repeated declarations of a dependency, keeping the first</li>
 *   <li>{@code action: replaceDependency, with: "g:a:v", alsoAdd?: "g:a:v"}: replaces the dependencies
 *       matching the rule's detect coordinates, and adds {@code alsoAdd} (e.g. an implementation) where a
 *       real dependency was replaced</li>
 * </ul>
 */
public final class MavenPomFixer implements Fixer {

    public static final String STRATEGY = "maven";

    private static String[] gav(String ruleId, String coordinates) {
        String[] gav = coordinates.split(":");
        if (gav.length != 3) {
            throw new IllegalArgumentException("Rule '" + ruleId + "': " + coordinates + " must be groupId:artifactId:version");
        }
        return gav;
    }

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
            for (String file : step.files()) {
                Path path = root.resolve(file);
                String before = Files.readString(path, StandardCharsets.UTF_8);
                PomEditor.Result result = switch (action) {
                    case "setScope" -> {
                        List<Pattern> coordinates = step.rule().detectParams().requiredStrings("coordinates").stream()
                                .map(DependencyDetector::glob).toList();
                        yield PomEditor.setDependencyScope(before,
                                (g, a) -> coordinates.stream().anyMatch(p -> p.matcher(g + ":" + a).matches()),
                                params.string("scope"));
                    }
                    case "setPluginVersion" -> PomEditor.setPluginVersion(before,
                            params.optString("groupId").orElse("org.apache.maven.plugins"),
                            params.string("plugin"), params.string("version"));
                    case "setProperty" -> PomEditor.setProperty(before, params.string("name"), params.string("value"));
                    case "addDependency" -> {
                        String content = before;
                        int changes = 0;
                        Optional<String> fixed = params.optString("dependency");
                        if (fixed.isPresent()) {
                            String[] gav = fixed.get().split(":");
                            if (gav.length != 3) {
                                throw new IllegalArgumentException("Rule '" + ruleId + "': dependency must be groupId:artifactId:version");
                            }
                            yield PomEditor.addDependency(content, gav[0], gav[1], gav[2], params.optString("scope").orElse(null));
                        }
                        for (Finding f : step.findings()) {
                            if (!f.file().equals(file) || !f.data().containsKey("artifactId")) {
                                continue;
                            }
                            PomEditor.Result added = PomEditor.addDependency(content, f.data().get("groupId"),
                                    f.data().get("artifactId"), f.data().get("version"), f.data().get("scope"));
                            content = added.content();
                            changes += added.changes();
                        }
                        yield new PomEditor.Result(content, changes);
                    }
                    case "setVersion" -> {
                        String content = before;
                        int changes = 0;
                        for (Finding f : step.findings()) {
                            if (!f.file().equals(file) || !f.data().containsKey("version")) {
                                continue;
                            }
                            PomEditor.Result set = PomEditor.setDependencyVersion(content, f.data().get("groupId"),
                                    f.data().get("artifactId"), f.data().get("version"));
                            content = set.content();
                            changes += set.changes();
                        }
                        yield new PomEditor.Result(content, changes);
                    }
                    case "removeDuplicates" -> PomEditor.removeDuplicateDependencies(before);
                    case "replaceDependency" -> {
                        List<Pattern> coordinates = step.rule().detectParams().requiredStrings("coordinates").stream()
                                .map(DependencyDetector::glob).toList();
                        String[] with = gav(ruleId, params.string("with"));
                        boolean realBefore = PomEditor.hasRealDependency(before, with[0], with[1]);
                        PomEditor.Result replaced = PomEditor.replaceDependency(before,
                                (g, a) -> coordinates.stream().anyMatch(p -> p.matcher(g + ":" + a).matches()), with[0], with[1], with[2]);
                        Optional<String> alsoAdd = params.optString("alsoAdd");
                        if (alsoAdd.isEmpty() || replaced.changes() == 0
                                || realBefore == PomEditor.hasRealDependency(replaced.content(), with[0], with[1])) {
                            yield replaced;
                        }
                        String[] extra = gav(ruleId, alsoAdd.get());
                        PomEditor.Result added = PomEditor.addDependency(replaced.content(), extra[0], extra[1], extra[2], null);
                        yield new PomEditor.Result(added.content(), replaced.changes() + added.changes());
                    }
                    default -> throw new IllegalArgumentException("Rule '" + ruleId + "': unknown maven action '" + action
                            + "'; use setScope, setPluginVersion, setProperty, addDependency, setVersion, removeDuplicates or replaceDependency");
                };
                if (result.changes() > 0) {
                    Files.writeString(path, result.content(), StandardCharsets.UTF_8);
                    changed++;
                }
                details.add(ruleId + ": " + file + ": " + (result.changes() > 0 ? action + " (" + result.changes()
                        + " place(s))" : "already correct"));
            }
        }
        return new StageResult(STRATEGY, StageResult.Status.APPLIED, changed + " build file edit(s) by "
                + steps.size() + " rule(s)", details);
    }
}
