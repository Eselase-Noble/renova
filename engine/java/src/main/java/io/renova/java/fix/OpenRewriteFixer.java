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
    /** The Gradle plugin built on the same OpenRewrite release (8.89) as the Maven plugin above. */
    static final String DEFAULT_GRADLE_PLUGIN = "org.openrewrite:plugin:7.39.0";
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
        List<Path> gradleRoots = GradleSupport.buildRoots(context);
        if (roots.isEmpty() && gradleRoots.isEmpty()) {
            return StageResult.skipped("recipe", "no Maven or Gradle build found");
        }
        List<String> details = new ArrayList<>();
        int succeeded = 0;
        for (Path root : gradleRoots) {
            Proc.Result result = runGradle(context, root, recipes, artifacts);
            String name = context.workspace().root().relativize(root).toString();
            details.add("== " + (name.isEmpty() ? "." : name) + " (gradle): exit " + result.exitCode());
            details.add(result.tail(result.ok() ? 8 : 40));
            if (result.ok()) {
                succeeded++;
            }
        }
        for (Path root : roots) {
            List<String> cmd = MavenSupport.baseCommand(context, root);
            cmd.add("-Drewrite.activeRecipes=" + String.join(",", recipes));
            if (!artifacts.isEmpty()) {
                cmd.add("-Drewrite.recipeArtifactCoordinates=" + String.join(",", artifacts));
            }
            cmd.add("-Drewrite.exportDatatables=false");
            if (isReactor(context, root)) {
                // runNoFork runs no build phases, so a module cannot resolve a sibling it depends on
                // and Maven fails the run. Including the compile phase lets Maven resolve siblings
                // from their output directories; maven.main.skip keeps it from compiling legacy code
                // that does not build on the target JDK until the recipes have run.
                cmd.add("-Dmaven.main.skip=true");
                cmd.add("compile");
            }
            cmd.add(plugin + ":" + goal);
            Proc.Result result = Proc.run(cmd, root, TIMEOUT);
            String name = context.workspace().root().relativize(root).toString();
            details.add("== " + (name.isEmpty() ? "." : name) + ": exit " + result.exitCode());
            details.add(result.tail(result.ok() ? 8 : 40));
            if (result.ok()) {
                succeeded++;
            }
        }
        int all = roots.size() + gradleRoots.size();
        StageResult.Status status = succeeded == all ? StageResult.Status.APPLIED
                : succeeded == 0 ? StageResult.Status.FAILED : StageResult.Status.PARTIAL;
        return new StageResult("recipe", status, recipes.size() + " recipe(s) on " + succeeded + "/" + all
                + " build root(s)", details);
    }

    /**
     * Runs the recipes on a Gradle build without touching its build files: an init script applies the
     * OpenRewrite plugin to the root project for this one invocation. The script is kept outside the workspace,
     * so it never becomes part of a stage's commit.
     */
    private static Proc.Result runGradle(MigrationContext context, Path root, Set<String> recipes, List<String> artifacts)
            throws Exception {
        String plugin = setting(context, "openrewrite.gradlePlugin", DEFAULT_GRADLE_PLUGIN);
        StringBuilder script = new StringBuilder();
        script.append("initscript {\n")
                // The plugin is on the plugin portal; what it depends on is on Maven Central.
                .append("    repositories {\n        maven { url = uri(\"https://plugins.gradle.org/m2\") }\n        mavenCentral()\n    }\n")
                .append("    dependencies { classpath(\"").append(plugin).append("\") }\n")
                .append("}\n")
                .append("rootProject {\n")
                .append("    plugins.apply(org.openrewrite.gradle.RewritePlugin)\n")
                .append("    dependencies {\n");
        artifacts.forEach(a -> script.append("        rewrite(\"").append(a).append("\")\n"));
        script.append("    }\n")
                // The recipe artifacts are resolved from the project's repositories; a build without any gets Central.
                .append("    afterEvaluate {\n")
                .append("        if (repositories.isEmpty()) {\n")
                .append("            repositories { mavenCentral() }\n")
                .append("        }\n")
                .append("    }\n")
                .append("}\n");
        Path init = java.nio.file.Files.createTempFile("renova-rewrite", ".init.gradle");
        try {
            java.nio.file.Files.writeString(init, script.toString());
            List<String> cmd = GradleSupport.baseCommand(context, root);
            cmd.add("--init-script");
            cmd.add(init.toString());
            cmd.add("rewriteRun");
            cmd.add("-Drewrite.activeRecipe=" + String.join(",", recipes));
            return Proc.run(cmd, root, TIMEOUT);
        } finally {
            java.nio.file.Files.deleteIfExists(init);
        }
    }

    private static boolean isReactor(MigrationContext context, Path buildRoot) {
        String path = context.workspace().root().relativize(buildRoot).toString().replace('\\', '/');
        String modulePath = path.isEmpty() ? "." : path;
        return context.project().modules().stream()
                .anyMatch(m -> m.path().equals(modulePath) && m.fact("modules") != null);
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
