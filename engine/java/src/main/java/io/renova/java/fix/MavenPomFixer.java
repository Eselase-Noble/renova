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
import java.util.regex.Pattern;

/**
 * Deterministic pom.xml edits driven by the playbook ({@code strategy: maven}):
 * <ul>
 *   <li>{@code action: setScope, scope: provided}: dependencies matching the rule's detect coordinates</li>
 *   <li>{@code action: setPluginVersion, plugin: maven-war-plugin, version: "3.4.0", groupId?}</li>
 *   <li>{@code action: setProperty, name: maven.compiler.target, value: "${maven.compiler.source}"}</li>
 *   <li>{@code action: addDependency}: adds the dependency each finding describes in its data
 *       (groupId, artifactId, version, scope)</li>
 * </ul>
 */
public final class MavenPomFixer implements Fixer {

    public static final String STRATEGY = "maven";

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
                    default -> throw new IllegalArgumentException("Rule '" + ruleId + "': unknown maven action '" + action
                            + "'; use setScope, setPluginVersion, setProperty or addDependency");
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
