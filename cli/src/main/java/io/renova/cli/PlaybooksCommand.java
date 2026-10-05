package io.renova.cli;

import io.renova.core.engine.PluginRegistry;
import io.renova.core.playbook.Playbook;
import io.renova.core.spi.EcosystemPlugin;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@Command(name = "playbooks", description = "List installed ecosystem plugins and bundled playbooks.")
final class PlaybooksCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        PluginRegistry registry = PluginRegistry.load();
        System.out.println("Ecosystems:");
        for (EcosystemPlugin plugin : registry.plugins()) {
            System.out.printf("  %-10s %s%n", plugin.id(), plugin.displayName());
        }
        System.out.println("\nPlaybooks:");
        for (Playbook p : registry.playbooks()) {
            System.out.printf("  %-28s %-6s %3d rules  %s%n", p.id(), p.ecosystem(), p.rules().size(), p.name());
        }
        return 0;
    }
}
