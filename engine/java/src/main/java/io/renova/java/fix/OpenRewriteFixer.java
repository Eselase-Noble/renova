package io.renova.java.fix;

import io.renova.core.engine.MigrationContext;
import io.renova.core.engine.PlanStep;
import io.renova.core.engine.StageResult;
import io.renova.core.playbook.FixSpec;
import io.renova.core.spi.Fixer;
import io.renova.core.util.Proc;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs the playbook's OpenRewrite recipes through the rewrite-maven-plugin. Maven resolves the
 * project's real classpath, so type-aware recipes are accurate. Uses {@code runNoFork} so the legacy
 * code does not have to compile on the new JDK first.
 */
public final class OpenRewriteFixer implements Fixer {

    static final String DEFAULT_PLUGIN = "org.openrewrite.maven:rewrite-maven-plugin:6.46.1";
    private static final Duration TIMEOUT = Duration.ofMinutes(60);

    @Override
    public String strategy() {
        return FixSpec.RECIPE;
    }

    @Override
    public StageResult apply(MigrationContext context, List<PlanStep> steps) throws Exception {
        Set<String> recipes = new LinkedHashSet<>();
        steps.forEach(s -> recipes.addAll(s.rule().fix().recipes()));
        String plugin = setting(context, "openrewrite.plugin", DEFAULT_PLUGIN);
        String goal = setting(context, "openrewrite.goal", "runNoFork");
        List<String> artifacts = settingList(context, "openrewrite.artifacts");

        List<Path> roots = MavenSupport.buildRoots(context);
        if (roots.isEmpty()) {
            return StageResult.skipped("recipe", "no Maven build found (Gradle support is on the roadmap)");
        }
        List<String> details = new ArrayList<>();
        int succeeded = 0;
        for (Path root : roots) {
            List<String> cmd = MavenSupport.baseCommand(context, root);
            cmd.add("-Drewrite.activeRecipes=" + String.join(",", recipes));
            if (!artifacts.isEmpty()) {
                cmd.add("-Drewrite.recipeArtifactCoordinates=" + String.join(",", artifacts));
            }
            cmd.add("-Drewrite.exportDatatables=false");
            cmd.add(plugin + ":" + goal);
            Proc.Result result = Proc.run(cmd, root, TIMEOUT);
            String name = context.workspace().root().relativize(root).toString();
            details.add("== " + (name.isEmpty() ? "." : name) + ": exit " + result.exitCode());
            details.add(result.tail(result.ok() ? 8 : 40));
            if (result.ok()) {
                succeeded++;
            }
        }
        StageResult.Status status = succeeded == roots.size() ? StageResult.Status.APPLIED
                : succeeded == 0 ? StageResult.Status.FAILED : StageResult.Status.PARTIAL;
        return new StageResult("recipe", status, recipes.size() + " recipe(s) on " + succeeded + "/" + roots.size()
                + " build root(s)", details);
    }

    private static String setting(MigrationContext context, String key, String fallback) {
        Object value = context.playbook().setting(key);
        return value == null ? fallback : value.toString();
    }

    private static List<String> settingList(MigrationContext context, String key) {
        Object value = context.playbook().setting(key);
        if (value instanceof Collection<?> c) {
            return c.stream().map(String::valueOf).toList();
        }
        return value == null ? List.of() : List.of(value.toString());
    }
}
