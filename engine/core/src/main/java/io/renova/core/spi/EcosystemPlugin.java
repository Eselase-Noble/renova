package io.renova.core.spi;

import io.renova.core.model.ProjectModel;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Everything the engine needs to migrate one ecosystem (Java, .NET, Python, COBOL, ...). Plugins are
 * discovered with {@link java.util.ServiceLoader}, so a new ecosystem is a new jar on the classpath.
 */
public interface EcosystemPlugin {

    /** Matches {@code ecosystem} in playbooks, e.g. "java". */
    String id();

    String displayName();

    /** Whether this plugin recognises the project at {@code root}. */
    boolean supports(Path root);

    ProjectModel model(Path root) throws IOException;

    default List<DetectorFactory> detectors() {
        return List.of();
    }

    default List<Fixer> fixers() {
        return List.of();
    }

    default Optional<Verifier> verifier() {
        return Optional.empty();
    }

    /** Classpath resources of the playbooks this plugin ships with. */
    default List<String> bundledPlaybooks() {
        return List.of();
    }
}
