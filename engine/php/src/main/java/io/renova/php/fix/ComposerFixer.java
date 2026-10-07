package io.renova.php.fix;

import io.renova.core.engine.MigrationContext;
import io.renova.core.engine.PlanStep;
import io.renova.core.engine.StageResult;
import io.renova.core.playbook.Params;
import io.renova.core.spi.Fixer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Fix strategy {@code composer}: changes to composer.json, driven by the rule's {@code fix.params}.
 *
 * <ul>
 *   <li>{@code action: setPhp, constraint: "^8.4"} sets the PHP version the project requires, and the pinned
 *       platform version where there is one.</li>
 *   <li>{@code action: setVersion, constraint: "^12.0"} sets the constraint of the packages the rule found.</li>
 *   <li>{@code action: add, packages: ["vendor/name:^1.0"], dev?: true} adds packages a project does not have.</li>
 *   <li>{@code action: remove} removes the packages the rule found.</li>
 *   <li>{@code action: replace, packages: ["new/name:^1.0"], dev?: true} removes the found packages and adds these.</li>
 * </ul>
 *
 * composer.lock is not edited: the verifier resolves the new constraints with {@code composer update}.
 */
public final class ComposerFixer implements Fixer {

    public static final String STRATEGY = "composer";
    private static final List<String> ACTIONS = List.of("setPhp", "setVersion", "add", "remove", "replace");

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
                throw new IllegalArgumentException("Rule '" + ruleId + "': unknown composer action '" + action + "'; use one of " + ACTIONS);
            }
            for (String file : step.files()) {
                if (!file.endsWith("composer.json")) {
                    continue;
                }
                Path path = root.resolve(file);
                String before = Files.readString(path, StandardCharsets.UTF_8);
                String json = before;
                List<String> found = step.findings().stream().filter(f -> f.file().equals(file) && f.data().containsKey("package"))
                        .map(f -> f.data().get("package")).distinct().toList();
                switch (action) {
                    case "setPhp" -> {
                        String constraint = params.string("constraint");
                        json = ComposerJson.requires(json, "php") ? ComposerJson.setConstraint(json, "php", constraint)
                                : ComposerJson.add(json, "php", constraint, false);
                        json = ComposerJson.setPlatformPhp(json, constraint.replaceAll("[^\\d.]", "") + ".0");
                    }
                    case "setVersion" -> {
                        for (String name : found) {
                            json = ComposerJson.setConstraint(json, name, params.string("constraint"));
                        }
                    }
                    case "remove", "replace" -> {
                        for (String name : found) {
                            json = ComposerJson.remove(json, name);
                        }
                    }
                    default -> { }
                }
                if (action.equals("add") || action.equals("replace")) {
                    boolean dev = params.optString("dev").map(Boolean::parseBoolean).orElse(false);
                    for (String one : params.requiredStrings("packages")) {
                        String[] nameConstraint = one.split(":", 2);
                        json = ComposerJson.add(json, nameConstraint[0], nameConstraint.length > 1 ? nameConstraint[1] : "*", dev);
                    }
                }
                if (!json.equals(before)) {
                    Files.writeString(path, json, StandardCharsets.UTF_8);
                    changed++;
                }
                details.add(ruleId + ": " + file + ": " + (json.equals(before) ? "already correct" : action));
            }
        }
        return new StageResult(STRATEGY, StageResult.Status.APPLIED, changed + " composer.json edit(s) by " + steps.size() + " rule(s)", details);
    }
}
