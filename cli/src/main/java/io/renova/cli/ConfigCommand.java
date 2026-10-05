package io.renova.cli;

import io.renova.core.ai.AiProvider;
import io.renova.core.ai.AiProviderFactory;
import io.renova.core.config.Secret;
import io.renova.core.config.Settings;
import io.renova.core.engine.PluginRegistry;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "config", description = "View and change Renova settings, including your own AI provider keys.",
        subcommands = {ConfigCommand.Show.class, ConfigCommand.Set.class, ConfigCommand.SetKey.class,
                ConfigCommand.Unset.class, ConfigCommand.Check.class})
final class ConfigCommand {

    @Command(name = "show", description = "Show effective settings and where each one comes from. Keys are masked.")
    static final class Show implements Callable<Integer> {
        @Mixin
        AiOptions ai;

        @Override
        public Integer call() throws Exception {
            AiConfiguration config = AiConfiguration.load(PluginRegistry.load(), ai);
            System.out.println("User config file: " + config.userConfig().file());
            System.out.println();
            List<String> keys = new ArrayList<>(List.of(AiConfiguration.PROVIDER, AiConfiguration.MODEL,
                    AiConfiguration.EFFORT, AiConfiguration.FALLBACKS, AiConfiguration.RAG, AiConfiguration.RAG_BUDGET));
            for (AiProviderFactory f : config.registry().aiProviders()) {
                keys.add(AiConfiguration.apiKeyKey(f.name()));
                keys.add(AiConfiguration.baseUrlKey(f.name()));
            }
            for (String key : keys) {
                Settings.Value value = config.find(key).orElse(null);
                String shown = value == null ? "(not set)"
                        : key.endsWith(".apiKey") ? Secret.of(value.value()).masked() + "   [" + value.source() + "]"
                        : value.value() + "   [" + value.source() + "]";
                System.out.printf("  %-20s %s%n", key, shown);
            }
            System.out.println();
            System.out.println("AI: " + config.describe());
            return 0;
        }
    }

    @Command(name = "set", description = "Store a setting in your user config file, e.g. 'renova config set ai.provider anthropic'.")
    static final class Set implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "KEY")
        String key;

        @Parameters(index = "1", paramLabel = "VALUE")
        String value;

        @Override
        public Integer call() throws Exception {
            if (key.endsWith(".apiKey")) {
                System.err.println("Refusing to take an API key on the command line (it would be kept in your shell history).");
                System.err.println("Use: renova config set-key " + key.substring(0, key.indexOf('.')));
                return 2;
            }
            AiConfiguration config = AiConfiguration.load(PluginRegistry.load(), null);
            config.userConfig().set(key, value);
            System.err.println("Saved " + key + " to " + config.userConfig().file());
            return 0;
        }
    }

    @Command(name = "set-key", description = "Store your API key for a provider. Prompts without echo, or reads one line from stdin.")
    static final class SetKey implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "PROVIDER", description = "e.g. anthropic")
        String provider;

        @Override
        public Integer call() throws Exception {
            PluginRegistry registry = PluginRegistry.load();
            if (registry.aiProvider(provider).isEmpty()) {
                throw new IllegalArgumentException("Unknown AI provider '" + provider + "'. Installed: "
                        + registry.aiProviders().stream().map(AiProviderFactory::name).toList());
            }
            Console console = System.console();
            String key;
            if (console != null) {
                char[] chars = console.readPassword("API key for %s (input hidden): ", provider);
                key = chars == null ? null : new String(chars);
            } else {
                key = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
            }
            Secret secret = Secret.of(key);
            if (secret == null) {
                System.err.println("No key entered; nothing saved.");
                return 2;
            }
            AiConfiguration config = AiConfiguration.load(registry, null);
            config.userConfig().set(AiConfiguration.apiKeyKey(provider), secret.reveal());
            System.err.println("Saved " + secret.masked() + " for " + provider + " to " + config.userConfig().file()
                    + " (readable only by you).");
            if (config.find(AiConfiguration.PROVIDER).isEmpty()) {
                System.err.println("Tip: make it the default with: renova config set ai.provider " + provider);
            }
            return 0;
        }
    }

    @Command(name = "unset", description = "Remove a setting (or stored key) from your user config file.")
    static final class Unset implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "KEY")
        String key;

        @Override
        public Integer call() throws Exception {
            AiConfiguration config = AiConfiguration.load(PluginRegistry.load(), null);
            boolean removed = config.userConfig().unset(key);
            System.err.println(removed ? "Removed " + key : key + " was not set in " + config.userConfig().file());
            return 0;
        }
    }

    @Command(name = "check", description = "Verify the configured AI provider: key accepted and model available. Generates nothing.")
    static final class Check implements Callable<Integer> {
        @Mixin
        AiOptions ai;

        @Override
        public Integer call() throws Exception {
            PluginRegistry registry = PluginRegistry.load();
            AiConfiguration config = AiConfiguration.load(registry, ai);
            System.err.println("AI: " + config.describe());
            try (AiProvider provider = registry.ai(config.aiSettings())) {
                if (!provider.available()) {
                    System.err.println("No AI provider selected. Set one with --ai or: renova config set ai.provider anthropic");
                    return 1;
                }
                System.out.println(provider.check());
            }
            return 0;
        }
    }
}
