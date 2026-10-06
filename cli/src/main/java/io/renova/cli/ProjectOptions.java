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

    @Option(names = "--with", split = ",", paramLabel = "ADDON",
            description = "Add-ons to combine with the target, for example junit5,log4j2 (see 'renova playbooks').")
    java.util.List<String> addons;

    Path root() {
        Path root = project.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Not a directory: " + root);
        }
        return root;
    }

    Playbook playbook(PluginRegistry registry) {
        Playbook target = playbook == null ? registry.defaultPlaybook(root()) : registry.playbook(playbook);
        return addons == null || addons.isEmpty() ? target : registry.playbook(target.id() + "+" + String.join("+", addons));
    }
}
