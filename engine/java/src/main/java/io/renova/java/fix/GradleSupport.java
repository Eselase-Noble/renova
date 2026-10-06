package io.renova.java.fix;

import io.renova.core.engine.MigrationContext;
import io.renova.core.model.Module;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** How to invoke Gradle in a workspace: the project's wrapper or gradle, offline mode, no daemon left behind. */
final class GradleSupport {

    private GradleSupport() {
    }

    static List<String> baseCommand(MigrationContext context, Path buildRoot) {
        List<String> cmd = new ArrayList<>();
        String executable = context.options().toolOption("gradle.executable");
        if (executable == null) {
            Path wrapper = buildRoot.resolve("gradlew");
            executable = Files.isRegularFile(wrapper) ? wrapper.toString() : "gradle";
        }
        if (executable.endsWith("gradlew") && !Files.isExecutable(Path.of(executable))) {
            // A wrapper checked in without its executable bit still runs through the shell.
            cmd.add("sh");
        }
        cmd.add(executable);
        // A daemon would outlive the migration and hold the workspace's files.
        cmd.add("--no-daemon");
        cmd.add("--console=plain");
        if ("true".equals(context.options().toolOption("gradle.offline")) || "true".equals(context.options().toolOption("maven.offline"))) {
            cmd.add("--offline");
        }
        return cmd;
    }

    private static final java.util.regex.Pattern DISTRIBUTION = java.util.regex.Pattern.compile(
            "(distributionUrl=.*?gradle-)(\\d+(?:\\.\\d+)*)(-(?:bin|all)\\.zip)");

    /** The Gradle version the project's wrapper runs, or null when it has no wrapper. */
    static String wrapperVersion(Path buildRoot) {
        Path properties = buildRoot.resolve("gradle/wrapper/gradle-wrapper.properties");
        try {
            java.util.regex.Matcher m = DISTRIBUTION.matcher(Files.readString(properties));
            return m.find() ? m.group(2) : null;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /**
     * Moves the wrapper to a Gradle that runs on Java {@code target}, when the one it names does not. Returns
     * what changed, for the report, or null when nothing had to. The checksum of the old distribution is
     * dropped with it; the wrapper jar itself works with any distribution.
     */
    static String upgradeWrapper(Path buildRoot, int target) throws java.io.IOException {
        String current = wrapperVersion(buildRoot);
        if (current == null || target == 0 || Jdks.newestJavaFor(current) >= target) {
            return null;
        }
        Path properties = buildRoot.resolve("gradle/wrapper/gradle-wrapper.properties");
        String wanted = Jdks.gradleFor(target);
        String text = DISTRIBUTION.matcher(Files.readString(properties)).replaceFirst("$1" + wanted + "$3")
                .replaceAll("(?m)^distributionSha256Sum=.*\\R?", "");
        Files.writeString(properties, text);
        return "Gradle wrapper moved from " + current + " to " + wanted + ": Gradle " + current + " runs on Java "
                + Jdks.newestJavaFor(current) + " at most, and the target is Java " + target;
    }

    /**
     * Directories to run Gradle in: Gradle modules not nested in another Gradle module (a root build covers its
     * subprojects), leaving out directories that also have a pom.xml, which are built with Maven.
     */
    static List<Path> buildRoots(MigrationContext context) {
        List<String> gradleDirs = context.project().modules().stream()
                .filter(m -> "gradle".equals(m.fact("buildTool")))
                .map(Module::path)
                .distinct()
                .toList();
        List<Path> roots = new ArrayList<>();
        for (String dir : gradleDirs) {
            boolean nested = gradleDirs.stream().anyMatch(other -> !other.equals(dir)
                    && (other.equals(".") || dir.startsWith(other + "/")));
            Path root = context.workspace().root().resolve(dir).normalize();
            if (!nested && !Files.exists(root.resolve("pom.xml"))) {
                roots.add(root);
            }
        }
        return roots;
    }
}
