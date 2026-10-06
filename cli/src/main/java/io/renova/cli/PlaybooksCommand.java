package io.renova.cli;

import io.renova.core.ai.AiProviderFactory;
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
        System.out.println("\nAI providers:");
        System.out.printf("  %-10s %s%n", "none", "No AI; AI steps are reported as manual work");
        for (AiProviderFactory ai : registry.aiProviders()) {
            System.out.printf("  %-10s %s (default model %s, key from %s)%n", ai.name(), ai.displayName(),
                    ai.defaultModel(), ai.apiKeyEnvironmentVariable());
        }
        System.out.println("\nTargets (--playbook ID):");
        for (Playbook p : registry.playbooks()) {
            if (!p.addon()) {
                System.out.printf("  %-34s %-6s %3d rules  %s%n", p.id(), p.ecosystem(), p.rules().size(), p.name());
            }
        }
        System.out.println("\nAdd-ons, combined with a target (--with ID,ID):");
        for (Playbook p : registry.addons()) {
            System.out.printf("  %-34s %-6s %3d rules  %s%n", p.id(), p.ecosystem(), p.rules().size(), p.name());
        }
        return 0;
    }
}
