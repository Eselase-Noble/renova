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

/** Builds every Maven build root and turns compiler output into structured errors for the AI loop. */
public final class MavenVerifier implements Verifier {

    private static final Pattern COMPILER_ERROR = Pattern.compile("^\\[ERROR\\] (.+?\\.(?:java|kt|groovy)):\\[(\\d+)(?:,\\d+)?\\] (.*)$");
    /** Plugin failures (bad build configuration, missing dependency): attributed to the build file. */
    private static final Pattern GOAL_FAILURE = Pattern.compile("^\\[ERROR\\] Failed to execute goal .*? on project [^:]+: (.*?)(?: -> \\[Help \\d+])?$");
    private static final Duration TIMEOUT = Duration.ofMinutes(60);

    @Override
    public VerifyResult verify(MigrationContext context) throws Exception {
        String goals = context.options().toolOptions().getOrDefault("maven.verifyGoals", "clean package -DskipTests");
        Path workspace = context.workspace().root();
        List<BuildError> errors = new ArrayList<>();
        StringBuilder log = new StringBuilder();
        boolean success = true;
        for (Path root : MavenSupport.buildRoots(context)) {
            List<String> cmd = MavenSupport.baseCommand(context, root);
            cmd.addAll(Arrays.asList(goals.split("\\s+")));
            Proc.Result result = Proc.run(cmd, root, TIMEOUT);
            log.append("== ").append(workspace.relativize(root)).append(": exit ").append(result.exitCode()).append('\n');
            if (!result.ok()) {
                success = false;
                log.append(result.tail(40)).append('\n');
                errors.addAll(parse(result.output(), workspace, root));
            }
        }
        return new VerifyResult(success, errors, log.toString());
    }

    static List<BuildError> parse(String output, Path workspace, Path buildRoot) {
        List<BuildError> errors = new ArrayList<>();
        for (String line : output.lines().toList()) {
            Matcher goal = GOAL_FAILURE.matcher(line);
            if (goal.matches()) {
                // Compilation failures repeat the per-file errors already collected; keep the rest.
                if (!goal.group(1).startsWith("Compilation failure")) {
                    String pom = workspace.relativize(buildRoot.resolve("pom.xml")).toString().replace('\\', '/');
                    errors.add(new BuildError(pom, 0, goal.group(1).strip()));
                }
                continue;
            }
            Matcher m = COMPILER_ERROR.matcher(line);
            if (m.matches()) {
                Path file = Path.of(m.group(1));
                String relative = file.isAbsolute() && file.startsWith(workspace)
                        ? workspace.relativize(file).toString().replace('\\', '/') : m.group(1);
                BuildError error = new BuildError(relative, Integer.parseInt(m.group(2)), m.group(3).strip());
                if (!errors.contains(error)) {
                    errors.add(error);
                }
            }
        }
        return errors;
    }
}
