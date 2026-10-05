package io.renova.core;

import io.renova.core.ai.AiSettings;
import io.renova.core.config.DotEnv;
import io.renova.core.config.Secret;
import io.renova.core.config.Settings;
import io.renova.core.config.UserConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigTest {

    @Test
    void secretsNeverAppearInToStringOrRecords() {
        Secret key = Secret.of("sk-ant-api03-abcdefghijklmnop-9f3a");
        assertThat(key.toString()).isEqualTo("<redacted>");
        assertThat(key.masked()).isEqualTo("sk-ant-…9f3a");
        assertThat(new AiSettings("anthropic", null, key, null, Map.of()).toString()).doesNotContain("abcdefgh");
        assertThat(Secret.of("  ")).isNull();
    }

    @Test
    void earlierSourcesWinAndReportTheirName() {
        Settings settings = new Settings(List.of(
                new Settings.Source("command line", Map.of("ai.model", "m1")),
                new Settings.Source("environment", Map.of("ai.model", "m2", "ai.provider", "anthropic"))));
        assertThat(settings.find("ai.model")).get().extracting(Settings.Value::value, Settings.Value::source)
                .containsExactly("m1", "command line");
        assertThat(settings.find("ai.provider")).get().extracting(Settings.Value::source).isEqualTo("environment");
        assertThat(settings.get("ai.effort")).isEmpty();
    }

    @Test
    void readsDotEnvFiles(@TempDir Path dir) throws Exception {
        Path env = dir.resolve(".env");
        Files.writeString(env, """
                # comment
                export ANTHROPIC_API_KEY="sk-ant-quoted"
                RENOVA_AI_MODEL=claude-opus-5-5 # trailing comment
                EMPTY=
                not a pair
                """);
        assertThat(DotEnv.read(env)).containsExactly(
                Map.entry("ANTHROPIC_API_KEY", "sk-ant-quoted"),
                Map.entry("RENOVA_AI_MODEL", "claude-opus-5-5"),
                Map.entry("EMPTY", ""));
    }

    @Test
    void userConfigIsPrivateAndRoundTrips(@TempDir Path dir) throws Exception {
        UserConfig config = new UserConfig(dir.resolve("renova/config.properties"));
        config.set("anthropic.apiKey", "sk-ant-secret");
        config.set("ai.provider", "anthropic");
        assertThat(config.read()).containsEntry("ai.provider", "anthropic").containsEntry("anthropic.apiKey", "sk-ant-secret");
        if (dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(config.file()))).isEqualTo("rw-------");
        }
        assertThat(config.unset("anthropic.apiKey")).isTrue();
        assertThat(config.read()).doesNotContainKey("anthropic.apiKey");
    }
}
