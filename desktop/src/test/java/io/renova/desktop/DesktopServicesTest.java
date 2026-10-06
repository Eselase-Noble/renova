package io.renova.desktop;

import io.renova.core.ai.AiSettings;
import io.renova.core.config.UserConfig;
import io.renova.core.engine.PluginRegistry;
import io.renova.core.config.AiPreferences;
import io.renova.desktop.service.RecentProjects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DesktopServicesTest {

    private final PluginRegistry registry = PluginRegistry.load();

    @Test
    void aiPreferencesSaveToTheUserConfigAndNeverShowTheKey(@TempDir Path dir) throws Exception {
        AiPreferences prefs = new AiPreferences(registry, new UserConfig(dir.resolve("config.properties")), Map.of());
        assertThat(prefs.aiSettings()).isEqualTo(AiSettings.NONE);
        assertThat(prefs.view().rag()).isTrue();

        prefs.save(AiPreferences.PROVIDER, "anthropic");
        prefs.setKey("anthropic", "sk-ant-test-0123456789abcdefWXYZ");
        AiPreferences.View view = prefs.view();
        assertThat(view.provider()).isEqualTo("anthropic");
        AiPreferences.Provider anthropic = view.providers().stream().filter(p -> p.name().equals("anthropic")).findFirst().orElseThrow();
        assertThat(anthropic.keyConfigured()).isTrue();
        assertThat(anthropic.maskedKey()).doesNotContain("0123456789abcdef").endsWith("WXYZ");
        assertThat(anthropic.keySource()).isEqualTo("Renova settings");
        assertThat(prefs.aiSettings().apiKey().reveal()).isEqualTo("sk-ant-test-0123456789abcdefWXYZ");

        prefs.save(AiPreferences.PROVIDER, "");
        assertThat(prefs.aiSettings()).isEqualTo(AiSettings.NONE);
    }

    @Test
    void environmentVariablesWinLikeInTheCli(@TempDir Path dir) throws Exception {
        UserConfig config = new UserConfig(dir.resolve("config.properties"));
        config.set("ai.provider", "openai");
        AiPreferences prefs = new AiPreferences(registry, config,
                Map.of("RENOVA_AI_PROVIDER", "anthropic", "ANTHROPIC_API_KEY", "sk-ant-from-env-ABCD"));
        assertThat(prefs.aiSettings().provider()).isEqualTo("anthropic");
        assertThat(prefs.view().providers()).filteredOn(p -> p.name().equals("anthropic"))
                .singleElement().satisfies(p -> assertThat(p.keySource()).isEqualTo("environment"));
    }

    @Test
    void recentProjectsAreNewestFirstWithoutDuplicatesOrMissingFolders(@TempDir Path dir) throws Exception {
        RecentProjects recent = new RecentProjects(dir.resolve("recent.json"));
        Path a = Files.createDirectories(dir.resolve("a"));
        Path b = Files.createDirectories(dir.resolve("b"));
        recent.opened(a);
        recent.opened(b);
        recent.opened(a);
        assertThat(recent.list()).extracting(RecentProjects.Entry::name).containsExactly("a", "b");
        Files.delete(b);
        assertThat(recent.list()).extracting(RecentProjects.Entry::name).containsExactly("a");
    }
}
