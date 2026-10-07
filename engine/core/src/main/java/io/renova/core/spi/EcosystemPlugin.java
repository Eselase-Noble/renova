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

    /**
     * File names that mark the root of a project of this ecosystem, such as {@code pom.xml}, or {@code *.sln}
     * for any file with that ending. A cheap hint for folder pickers; {@link #supports} stays the real check.
     */
    default List<String> projectMarkers() {
        return List.of();
    }

    /** Whether {@code dir} holds a file one of the markers names; only the folder itself is looked at. */
    static boolean marked(Path dir, List<String> markers) {
        List<String> endings = markers.stream().filter(m -> m.startsWith("*")).map(m -> m.substring(1)).toList();
        if (markers.stream().anyMatch(m -> !m.startsWith("*") && java.nio.file.Files.isRegularFile(dir.resolve(m)))) {
            return true;
        }
        if (endings.isEmpty()) {
            return false;
        }
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(dir)) {
            return files.anyMatch(f -> endings.stream().anyMatch(e -> f.getFileName().toString().endsWith(e))
                    && java.nio.file.Files.isRegularFile(f));
        } catch (IOException | java.io.UncheckedIOException e) {
            return false;
        }
    }

    /**
     * Why a folder that looks like one of this ecosystem's projects cannot be migrated, with what to do about
     * it; empty when the folder is simply not this ecosystem's. Shown instead of "nothing supports this".
     */
    default Optional<String> unsupportedReason(Path root) {
        return Optional.empty();
    }

    /**
     * Which of this ecosystem's playbooks to use for the project when the user has not chosen one.
     *
     * @param candidates ids of the installed targets of this ecosystem, never empty
     * @return one of them, optionally with add-ons joined by {@code +}
     */
    default String recommendedPlaybook(Path root, List<String> candidates) {
        return candidates.getFirst();
    }

    /**
     * Makes the migration's copy of the project something this ecosystem's tools can work on, before anything
     * else runs: for example a build file for a project that has none the tools understand. The project is
     * analysed again afterwards, so rules see the prepared project.
     *
     * @param workspace the copy; the original is never touched
     * @param options   the migration's tool options
     * @return the stage to report, or empty when there was nothing to prepare
     */
    default Optional<io.renova.core.engine.StageResult> prepare(Path workspace, java.util.Map<String, String> options)
            throws Exception {
        return Optional.empty();
    }

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
