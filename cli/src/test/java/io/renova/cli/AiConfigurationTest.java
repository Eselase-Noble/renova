package io.renova.cli;

import io.renova.core.engine.PluginRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiConfigurationTest {

    @Test
    void mapsConventionalEnvironmentVariablesToCanonicalKeys() {
        Map<String, String> mapped = AiConfiguration.fromEnvironment(PluginRegistry.load(), Map.of(
                "ANTHROPIC_API_KEY", "sk-ant-x",
                "ANTHROPIC_BASE_URL", "https://gateway.example",
                "RENOVA_AI_PROVIDER", "anthropic",
                "RENOVA_AI_MODEL", "claude-sonnet-5-5",
                "UNRELATED", "ignored"));
        assertThat(mapped).containsExactlyInAnyOrderEntriesOf(Map.of(
                "anthropic.apiKey", "sk-ant-x",
                "anthropic.baseUrl", "https://gateway.example",
                "ai.provider", "anthropic",
                "ai.model", "claude-sonnet-5-5"));
    }
}
