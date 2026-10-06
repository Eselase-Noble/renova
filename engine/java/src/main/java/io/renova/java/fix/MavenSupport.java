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
        cmd.add("-ntp");
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
