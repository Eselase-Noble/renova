package io.renova.core.engine;

import io.renova.core.ai.AiSettings;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * @param outputDir       where the migrated copy is created; must not exist or be empty
 * @param ai              AI provider and the caller's credentials; {@link AiSettings#NONE} to disable
 * @param maxAiIterations build-repair rounds before giving up
 * @param toolOptions     ecosystem-specific switches, e.g. {@code maven.settings} or {@code maven.offline}
 */
public record MigrationOptions(Path outputDir, AiSettings ai, int maxAiIterations, boolean verify,
                               Map<String, String> toolOptions, List<String> skipStrategies) {

    public MigrationOptions {
        outputDir = outputDir.toAbsolutePath().normalize();
        ai = ai == null ? AiSettings.NONE : ai;
        toolOptions = Map.copyOf(toolOptions);
        skipStrategies = List.copyOf(skipStrategies);
    }

    public String toolOption(String key) {
        return toolOptions.get(key);
    }
}
