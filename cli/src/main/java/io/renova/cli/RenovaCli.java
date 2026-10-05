package io.renova.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "renova",
        mixinStandardHelpOptions = true,
        version = "Renova 0.1.0",
        description = "Assess and migrate legacy systems with declarative playbooks.",
        subcommands = {AnalyzeCommand.class, MigrateCommand.class, PlaybooksCommand.class, ConfigCommand.class,
                CommandLine.HelpCommand.class})
public final class RenovaCli {

    public static void main(String[] args) {
        System.exit(new CommandLine(new RenovaCli())
                .setExecutionExceptionHandler((e, cmd, parse) -> {
                    cmd.getErr().println("error: " + e.getMessage());
                    if (System.getenv("RENOVA_DEBUG") != null) {
                        e.printStackTrace(cmd.getErr());
                    }
                    return 2;
                })
                .execute(args));
    }
}
