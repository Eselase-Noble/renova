package io.renova.java.fix;

import io.renova.core.engine.MigrationContext;
import io.renova.core.model.Module;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** How to invoke Maven in a workspace: wrapper or mvn, settings file, offline mode. */
final class MavenSupport {

    private MavenSupport() {
    }

    /**
     * The environment build tools run in: JAVA_HOME pointing at a JDK that can build the migration's target.
     * JAVA_HOME as it is set wins when it is new enough; otherwise the installed JDK that fits best. Without
     * this, a wrapper script picks a JDK by its own rules (the javac on the PATH, which may be another version
     * than java), and the build is verified on a JDK nobody chose.
     */
    static java.util.Map<String, String> environment(MigrationContext context) {
        return buildJdk(context).map(jdk -> java.util.Map.of("JAVA_HOME", jdk.home().toString())).orElse(java.util.Map.of());
    }

    /** The JDK the migrated project is built with; empty when none that is installed can build the target. */
    static java.util.Optional<Jdks.Jdk> buildJdk(MigrationContext context) {
        return Jdks.forTarget(Jdks.installed(), targetJava(context));
    }

    /** The Java version the playbook migrates to; 0 when it does not name one. */
    static int targetJava(MigrationContext context) {
        String target = context.playbook().targets().get("java");
        return target != null && target.matches("\\d+") ? Integer.parseInt(target) : 0;
    }

    static List<String> baseCommand(MigrationContext context, Path buildRoot) {
        List<String> cmd = new ArrayList<>();
        String executable = context.options().toolOption("maven.executable");
        if (executable == null) {
            Path wrapper = buildRoot.resolve("mvnw");
            executable = Files.isExecutable(wrapper) ? wrapper.toString() : "mvn";
        }
        cmd.add(executable);
        cmd.add("-B");
        // Quiet downloads, in the form every Maven 3 understands: a project's wrapper may run one from before
        // --no-transfer-progress existed (3.6.1), which answers an unknown option with its help text.
        cmd.add("-Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.transfer.Slf4jMavenTransferListener=warn");
        String settings = context.options().toolOption("maven.settings");
        if (settings != null) {
            cmd.add("-s");
            cmd.add(Path.of(settings).toAbsolutePath().toString());
        }
        if ("true".equals(context.options().toolOption("maven.offline"))) {
            cmd.add("-o");
        } else {
            // A download that stalls would otherwise hold the migration for ever: give up on a silent connection
            // after two minutes and let Maven try again. (Maven 3 reads the first set, Maven 4 and 3.9 the second.)
            cmd.add("-Dmaven.wagon.http.connectionTimeout=30000");
            cmd.add("-Dmaven.wagon.rto=120000");
            cmd.add("-Dmaven.wagon.http.retryHandler.count=3");
            cmd.add("-Daether.connector.connectTimeout=30000");
            cmd.add("-Daether.connector.requestTimeout=120000");
        }
        return cmd;
    }

    /** The oldest Maven the plugins of current frameworks run on (Spring Boot 3 asks for 3.6.3). */
    static final String MINIMUM_WRAPPER = "3.6.3";
    static final String NEW_WRAPPER = "3.9.9";
    private static final java.util.regex.Pattern WRAPPER_MAVEN = java.util.regex.Pattern.compile(
            "(distributionUrl\\s*=.*?/apache-maven/)([0-9][\\w.\\-]*)(/apache-maven-)\\2(-bin\\.(?:zip|tar\\.gz))");

    /**
     * Moves a Maven wrapper that names a Maven too old for the migrated build to a current one, as the Gradle
     * wrapper is moved: the migrated project must build with its own wrapper.
     *
     * @return what was done, or null when there is no wrapper or it is new enough
     */
    static String upgradeWrapper(Path buildRoot) throws java.io.IOException {
        Path properties = buildRoot.resolve(".mvn/wrapper/maven-wrapper.properties");
        if (!Files.isRegularFile(properties)) {
            return null;
        }
        String text = Files.readString(properties);
        java.util.regex.Matcher m = WRAPPER_MAVEN.matcher(text);
        if (!m.find() || !io.renova.core.util.Versions.isBelow(m.group(2), MINIMUM_WRAPPER)) {
            return null;
        }
        Files.writeString(properties, text.substring(0, m.start()) + m.group(1) + NEW_WRAPPER + m.group(3) + NEW_WRAPPER + m.group(4)
                + text.substring(m.end()));
        return "Maven wrapper " + m.group(2) + " → " + NEW_WRAPPER + ": the migrated build's plugins need Maven " + MINIMUM_WRAPPER
                + " or later";
    }

    /**
     * Directories to run Maven in: Maven modules not nested in another Maven module. A reactor root
     * covers its children; independent sibling projects each get their own run.
     */
    static List<Path> buildRoots(MigrationContext context) {
        List<String> mavenDirs = context.project().modules().stream()
                .filter(m -> "maven".equals(m.fact("buildTool")))
                .map(Module::path)
                .toList();
        List<Path> roots = new ArrayList<>();
        for (String dir : mavenDirs) {
            boolean nested = mavenDirs.stream().anyMatch(other -> !other.equals(dir)
                    && (other.equals(".") || dir.startsWith(other + "/")));
            if (!nested) {
                roots.add(context.workspace().root().resolve(dir).normalize());
            }
        }
        return roots;
    }
}
