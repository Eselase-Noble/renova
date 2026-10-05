package io.renova.core.engine;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * @param outputDir       where the migrated copy is created; must not exist or be empty
 * @param ai              name of the AI provider, "none" to disable
 * @param maxAiIterations build-repair rounds before giving up
 * @param toolOptions     ecosystem-specific switches, e.g. {@code maven.settings} or {@code maven.offline}
 */
public record MigrationOptions(Path outputDir, String ai, int maxAiIterations, boolean verify,
                               Map<String, String> toolOptions, List<String> skipStrategies) {

    public MigrationOptions {
        outputDir = outputDir.toAbsolutePath().normalize();
        toolOptions = Map.copyOf(toolOptions);
        skipStrategies = List.copyOf(skipStrategies);
    }

    public String toolOption(String key) {
        return toolOptions.get(key);
    }
}
