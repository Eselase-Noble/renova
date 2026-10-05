package io.renova.cli;

import io.renova.core.ai.NoAiProvider;
import io.renova.core.behaviour.BehaviourReport;
import io.renova.core.behaviour.BehaviourVerifier;
import io.renova.core.engine.MigrationContext;
import io.renova.core.engine.MigrationOptions;
import io.renova.core.engine.PluginRegistry;
import io.renova.core.model.ProjectModel;
import io.renova.core.playbook.Playbook;
import io.renova.core.spi.EcosystemPlugin;
import io.renova.core.workspace.Workspace;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "verify-behaviour",
        description = "Run the original and the migrated application of a migration workspace side by side "
                + "(in Docker) and compare their answers. The migrated project must already be built.")
final class VerifyBehaviourCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "WORKSPACE", description = "A workspace created by 'renova migrate'.")
    Path workspaceDir;

    @Option(names = {"-p", "--playbook"}, paramLabel = "ID|FILE", description = "Playbook used for the migration. Default: auto-detect.")
    String playbookRef;

    @Option(names = "--maven-settings", paramLabel = "FILE", description = "Maven settings.xml for building the original.")
    Path mavenSettings;

    @Option(names = "--offline", description = "Build the original offline.")
    boolean offline;

    @Option(names = "--scenarios", paramLabel = "FILE", description = "Scenario file. Default: renova-scenarios.yaml in the workspace, if present.")
    Path scenarios;

    @Override
    public Integer call() throws Exception {
        PluginRegistry registry = PluginRegistry.load();
        Workspace workspace = Workspace.open(workspaceDir);
        Playbook playbook = playbookRef == null ? registry.defaultPlaybook(workspace.root()) : registry.playbook(playbookRef);
        EcosystemPlugin plugin = registry.plugin(playbook.ecosystem());
        ProjectModel model = plugin.model(workspace.root());
        Map<String, String> tools = new LinkedHashMap<>();
        if (mavenSettings != null) {
            tools.put("maven.settings", mavenSettings.toString());
        }
        if (offline) {
            tools.put("maven.offline", "true");
        }
        if (scenarios != null) {
            tools.put(BehaviourVerifier.SCENARIOS_OPTION, scenarios.toAbsolutePath().toString());
        }
        MigrationOptions options = new MigrationOptions(workspace.root(), null, 0, false, tools, List.of());
        BehaviourReport report = BehaviourVerifier.verify(new MigrationContext(workspace, model, playbook, options,
                new NoAiProvider(), plugin), msg -> System.err.println("» " + msg));
        System.err.println("Behaviour: " + report.status() + ": " + report.summary());
        System.err.println("Report:    " + workspace.lcDir().resolve("behaviour.md"));
        return switch (report.status()) {
            case SAME, SKIPPED -> 0;
            case DIFFERENT -> 1;
            case FAILED -> 3;
        };
    }
}
