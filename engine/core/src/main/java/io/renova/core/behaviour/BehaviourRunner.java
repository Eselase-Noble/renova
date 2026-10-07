package io.renova.core.behaviour;

import io.renova.core.engine.MigrationContext;
import io.renova.core.model.ProjectModel;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The ecosystem-specific part of behavioural verification: which projects can be run, which requests
 * to send, and how to build and deploy the original and the migrated version. The engine runs both in
 * sandboxes, sends the requests and compares the answers.
 */
public interface BehaviourRunner {

    /** Why this project cannot be run side by side, or empty when it can. */
    Optional<String> unsupported(ProjectModel model);

    /** Requests that exercise the application's entry points, found in the project at {@code root}. */
    List<Scenario> discover(ProjectModel model, Path root);

    /**
     * Builds what both versions need and says how to run them: the original from {@code originalSource}
     * (the workspace's baseline commit) on its legacy platform, the migrated workspace on the target platform.
     */
    Deployments prepare(MigrationContext context, Path originalSource, Path workDir, Consumer<String> progress) throws Exception;

    /** Every entry point and the file that handles it, for sending differences to the code behind them. */
    default List<Route> routes(ProjectModel model, Path root) {
        return List.of();
    }

    /**
     * Environment variables that give this project's application these settings, e.g. for Java the JVM
     * system properties in {@code CATALINA_OPTS}. Used to point each application at its own sandbox database.
     */
    default Map<String, String> environment(ProjectModel model, Map<String, String> settings) {
        return Map.of();
    }

    record Deployments(AppDeployment baseline, AppDeployment candidate) {
    }
}
