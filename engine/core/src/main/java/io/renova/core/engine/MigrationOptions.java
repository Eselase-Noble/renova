package io.renova.core.engine;

import io.renova.core.ai.AiSettings;
import io.renova.core.rag.RagSettings;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * @param outputDir       where the migrated copy is created; must not exist or be empty
 * @param ai              AI provider and the caller's credentials; {@link AiSettings#NONE} to disable
 * @param maxAiIterations build-repair rounds before giving up
 * @param toolOptions     ecosystem-specific switches, e.g. {@code maven.settings} or {@code maven.offline}
 * @param rag             retrieval of extra context for AI requests
 */
public record MigrationOptions(Path outputDir, AiSettings ai, int maxAiIterations, boolean verify,
                               Map<String, String> toolOptions, List<String> skipStrategies, RagSettings rag) {

    public MigrationOptions {
        outputDir = outputDir.toAbsolutePath().normalize();
        ai = ai == null ? AiSettings.NONE : ai;
        toolOptions = Map.copyOf(toolOptions);
        skipStrategies = List.copyOf(skipStrategies);
        rag = rag == null ? RagSettings.ON : rag;
    }

    public MigrationOptions(Path outputDir, AiSettings ai, int maxAiIterations, boolean verify,
                            Map<String, String> toolOptions, List<String> skipStrategies) {
        this(outputDir, ai, maxAiIterations, verify, toolOptions, skipStrategies, RagSettings.ON);
    }

    public String toolOption(String key) {
        return toolOptions.get(key);
    }
}
