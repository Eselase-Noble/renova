package io.renova.java.fix;

import io.renova.core.engine.BuildError;
import io.renova.core.engine.MigrationContext;
import io.renova.core.engine.VerifyResult;
import io.renova.core.spi.Verifier;
import io.renova.core.util.Proc;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds and tests every build root of a Java project: Maven here, Gradle through {@link GradleVerifier}.
 * Builds and tests every Maven build root ({@code clean verify} by default) and turns compiler,
 * project-model and test failures into structured errors for the AI repair loop.
 */
public final class MavenVerifier implements Verifier {

    private static final Pattern COMPILER_ERROR = Pattern.compile("^\\[ERROR\\] (.+?\\.(?:java|kt|groovy)):\\[(\\d+)(?:,\\d+)?\\] (.*)$");
    /** Plugin failures (bad build configuration, missing dependency): attributed to the build file. */
    private static final Pattern GOAL_FAILURE = Pattern.compile("^\\[ERROR\\] Failed to execute goal .*? on project [^:]+: (.*?)(?: -> \\[Help \\d+])?$");
    /** Project-model errors: "The project g:a:v (/path/pom.xml) has 1 error", then the errors with "@ line N". */
    private static final Pattern MODEL_PROJECT = Pattern.compile("^\\[ERROR\\]\\s+The project \\S+ \\((.+?pom[^)]*\\.xml)\\) has \\d+ errors?");
    private static final Pattern MODEL_ERROR = Pattern.compile("^\\[ERROR\\]\\s+(.+?) @ (?:.*?, )?line (\\d+), column \\d+");
    /** Runs the project's tests: compiling is not enough to show the migrated code still works. */
    public static final String DEFAULT_GOALS = "clean verify";
    public static final String SKIP_TESTS_GOALS = "clean package -DskipTests";
    private static final Duration TIMEOUT = Duration.ofMinutes(60);

    /** The build needs an installed JDK that can compile for the target; Renova finds it (see {@link Jdks}). */
    @Override
    public void preflight(MigrationContext context) {
        int target = MavenSupport.targetJava(context);
        if (target == 0 || MavenSupport.buildJdk(context).isPresent()) {
            return;
        }
        java.util.List<Jdks.Jdk> jdks = Jdks.installed();
        int newest = jdks.stream().mapToInt(Jdks.Jdk::feature).max().orElse(Runtime.version().feature());
        throw new IllegalStateException("This migration targets Java " + target + ", and no JDK " + target + " or newer is installed "
                + "(found: " + Jdks.describe(jdks) + "). Install JDK " + target + " (Renova looks in JAVA_HOME, ~/.jdks, "
                + "~/.sdkman, /usr/lib/jvm and the folders in RENOVA_JDKS), or choose a target this machine can build "
                + "(for example with --playbook java-to-" + newest + "). Use --no-verify to migrate without building.");
    }

    @Override
    public VerifyResult verify(MigrationContext context) throws Exception {
        String goals = context.options().toolOptions().getOrDefault("maven.verifyGoals",
                "true".equals(context.options().toolOption("verify.skipTests")) ? SKIP_TESTS_GOALS : DEFAULT_GOALS);
        Path workspace = context.workspace().root();
        List<BuildError> errors = new ArrayList<>();
        StringBuilder log = new StringBuilder();
        boolean success = true;
        // Gradle builds in the same project are verified the same way, with their own tool.
        List<Path> gradleRoots = GradleSupport.buildRoots(context);
        if (!gradleRoots.isEmpty()) {
            VerifyResult gradle = GradleVerifier.verify(context, gradleRoots);
            success = gradle.success();
            errors.addAll(gradle.errors());
            log.append(gradle.log());
        }
        for (Path root : MavenSupport.buildRoots(context)) {
            List<String> cmd = MavenSupport.baseCommand(context, root);
            cmd.addAll(Arrays.asList(goals.split("\\s+")));
            Proc.Result result = Proc.run(cmd, root, TIMEOUT, MavenSupport.environment(context));
            log.append("== ").append(workspace.relativize(root)).append(": exit ").append(result.exitCode()).append('\n');
            if (!result.ok()) {
                success = false;
                log.append(result.tail(40)).append('\n');
                errors.addAll(parse(result.output(), workspace, root));
                errors.addAll(TestReports.parse(workspace, root));
            }
        }
        return new VerifyResult(success, errors, log.toString());
    }

    static List<BuildError> parse(String output, Path workspace, Path buildRoot) {
        List<BuildError> errors = new ArrayList<>();
        String modelPom = null;
        for (String line : output.lines().toList()) {
            Matcher project = MODEL_PROJECT.matcher(line);
            if (project.find()) {
                modelPom = relative(Path.of(project.group(1)), workspace);
                continue;
            }
            Matcher model = MODEL_ERROR.matcher(line);
            if (modelPom != null && model.find()) {
                BuildError error = new BuildError(modelPom, Integer.parseInt(model.group(2)), model.group(1).strip());
                if (!errors.contains(error)) {
                    errors.add(error);
                }
                continue;
            }
            Matcher goal = GOAL_FAILURE.matcher(line);
            if (goal.matches()) {
                // Compilation failures repeat the per-file errors already collected; keep the rest.
                // Compilation and test failures are reported in detail elsewhere; keep the rest.
                String message = goal.group(1);
                if (!message.startsWith("Compilation failure") && !message.startsWith("There are test failures")
                        && !message.startsWith("There was a timeout")) {
                    String pom = workspace.relativize(buildRoot.resolve("pom.xml")).toString().replace('\\', '/');
                    errors.add(new BuildError(pom, 0, goal.group(1).strip()));
                }
                continue;
            }
            Matcher m = COMPILER_ERROR.matcher(line);
            if (m.matches()) {
                BuildError error = new BuildError(relative(Path.of(m.group(1)), workspace), Integer.parseInt(m.group(2)),
                        m.group(3).strip());
                if (!errors.contains(error)) {
                    errors.add(error);
                }
            }
        }
        return errors;
    }

    private static String relative(Path file, Path workspace) {
        return file.isAbsolute() && file.startsWith(workspace)
                ? workspace.relativize(file).toString().replace('\\', '/') : file.toString().replace('\\', '/');
    }
}
