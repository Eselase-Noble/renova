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
