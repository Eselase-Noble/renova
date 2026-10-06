package io.renova.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.renova.core.engine.PluginRegistry;
import io.renova.core.engine.Portfolio;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "portfolio",
        description = "Assess every project under a folder and rank them, easiest to migrate first. Never modifies anything.")
final class PortfolioCommand implements Callable<Integer> {

    enum Format { md, json, csv }

    @Parameters(paramLabel = "FOLDER", description = "A folder holding several projects, at any depth up to --depth.")
    Path folder;

    @Option(names = "--depth", defaultValue = "4", description = "How many folders down to look for projects (default: ${DEFAULT-VALUE}).")
    int depth;

    @Option(names = {"-f", "--format"}, defaultValue = "md", description = "Output format: ${COMPLETION-CANDIDATES}.")
    Format format;

    @Option(names = {"-o", "--output"}, paramLabel = "FILE", description = "Write the report to a file instead of stdout.")
    Path output;

    @Override
    public Integer call() throws Exception {
        if (!Files.isDirectory(folder)) {
            System.err.println("No such folder: " + folder);
            return 2;
        }
        Portfolio.Result result = new Portfolio(PluginRegistry.load()).assess(folder, depth, line -> System.err.println("» " + line));
        String report = switch (format) {
            case json -> new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(result.entries());
            case csv -> Portfolio.csv(result);
            case md -> Portfolio.markdown(result);
        };
        if (output == null) {
            System.out.println(report);
        } else {
            Files.writeString(output, report);
        }
        System.err.printf("%d projects, %d findings, %d%% automated, %d fully automatic.%s%n", result.entries().size(), result.findings(),
                Math.round(result.automationRate() * 100), result.fullyAutomatic(), output == null ? "" : " Report: " + output.toAbsolutePath());
        return 0;
    }
}
