package io.renova.cli;

import io.renova.core.engine.PluginRegistry;
import io.renova.core.playbook.Playbook;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;

/** Options shared by commands that work on a project. */
final class ProjectOptions {

    @Parameters(index = "0", paramLabel = "PROJECT", description = "Root directory of the project.")
    Path project;

    @Option(names = {"-p", "--playbook"}, paramLabel = "ID|FILE",
            description = "Bundled playbook id or path to a playbook YAML. Default: the only bundled playbook that applies.")
    String playbook;

    Path root() {
        Path root = project.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Not a directory: " + root);
        }
        return root;
    }

    Playbook playbook(PluginRegistry registry) {
        return playbook == null ? registry.defaultPlaybook(root()) : registry.playbook(playbook);
    }
}
