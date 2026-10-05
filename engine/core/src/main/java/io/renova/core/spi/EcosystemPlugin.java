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

    /**
     * Files to show the AI alongside {@code file} (project-relative), such as the build file that
     * declares its dependencies. Lets one request fix a source file and its build file together.
     */
    default List<RelatedFile> relatedFiles(ProjectModel model, String file) {
        return List.of();
    }

    /**
     * Project files that help an AI understand {@code file} but are not changed with it: for Java,
     * the project types it extends or imports and the configuration files that name it. Used by
     * retrieval (RAG); every result is sent as reference only, whatever its {@code editable} flag.
     *
     * @param root the directory to read, normally the migration workspace, so results reflect
     *             code as already migrated
     */
    default List<RelatedFile> referencedFiles(ProjectModel model, Path root, String file) {
        return List.of();
    }

    /**
     * Whether {@code file} is a test. Tests define the behaviour a migration must keep, so AI requests
     * never offer them as editable: a failing test is fixed in the code under test or the build.
     */
    default boolean isTestFile(String file) {
        return false;
    }

    /** Runs the original and the migrated application side by side; empty when the ecosystem cannot. */
    default Optional<io.renova.core.behaviour.BehaviourRunner> behaviourRunner() {
        return Optional.empty();
    }

    /** Classpath resources of the playbooks this plugin ships with. */
    default List<String> bundledPlaybooks() {
        return List.of();
    }
}
