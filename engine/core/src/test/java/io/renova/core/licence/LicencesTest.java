package io.renova.core.licence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Licences are checked on the machine, against the vendor's public key, and gate migrating only. */
class LicencesTest {

    private static final KeyPair VENDOR = LicenceFile.newKeyPair();
    private static final LocalDate TODAY = LocalDate.now();

    private static String file(String licensee, List<String> ecosystems, LocalDate expires) {
        return LicenceFile.write(new Licence("RNV-1", licensee, "team", ecosystems, 5, TODAY, expires), VENDOR.getPrivate());
    }

    @Test
    void aLicenceAllowsMigratingTheEcosystemsItCoversUntilItExpires(@TempDir Path dir) throws Exception {
        Path installed = dir.resolve("config/licence.json");
        Licences licences = new Licences(installed, VENDOR.getPublic(), true);
        assertThat(licences.status().describe()).isEqualTo("No licence installed");
        assertThatThrownBy(() -> licences.requireMigration("java", "Java")).isInstanceOf(LicenceException.class)
                .hasMessageContaining("none is installed").hasMessageContaining("Assessing projects stays free");

        Path sent = Files.writeString(dir.resolve("acme.json"), file("Acme", List.of("java"), TODAY.plusDays(30)));
        assertThat(licences.install(sent).licensee()).isEqualTo("Acme");

        assertThat(licences.status().valid()).isTrue();
        assertThatCode(() -> licences.requireMigration("java", "Java")).doesNotThrowAnyException();
        assertThatThrownBy(() -> licences.requireMigration("dotnet", ".NET")).hasMessageContaining("covers java, not .NET");
        // The day after the last day.
        assertThat(licences.status(TODAY.plusDays(30)).valid()).isTrue();
        assertThat(licences.status(TODAY.plusDays(31)).describe()).contains("expired on");
    }

    @Test
    void aLicenceForEverythingCoversEcosystemsAddedLater(@TempDir Path dir) throws Exception {
        Licences licences = new Licences(dir.resolve("licence.json"), VENDOR.getPublic(), true);
        licences.install(Files.writeString(dir.resolve("all.json"), file("Acme", List.of("*"), TODAY.plusYears(1))));
        assertThatCode(() -> licences.requireMigration("php", "PHP")).doesNotThrowAnyException();
    }

    @Test
    void aChangedForgedOrExpiredFileIsRefused(@TempDir Path dir) throws Exception {
        Licences licences = new Licences(dir.resolve("licence.json"), VENDOR.getPublic(), true);
        String genuine = file("Acme", List.of("java"), TODAY.plusDays(30));

        // More seats written into the payload: the signature no longer matches.
        String payload = new String(java.util.Base64.getDecoder().decode(genuine.split("\"payload\" : \"")[1].split("\"")[0]));
        String edited = genuine.replace(genuine.split("\"payload\" : \"")[1].split("\"")[0],
                java.util.Base64.getEncoder().encodeToString(payload.replace("\"seats\":5", "\"seats\":500").getBytes()));
        assertThatThrownBy(() -> licences.install(Files.writeString(dir.resolve("edited.json"), edited)))
                .isInstanceOf(LicenceException.class).hasMessageContaining("changed after it was issued");

        // Signed with somebody else's key.
        String forged = LicenceFile.write(new Licence("X", "Acme", "team", List.of("*"), 5, TODAY, TODAY.plusYears(9)),
                LicenceFile.newKeyPair().getPrivate());
        assertThatThrownBy(() -> licences.install(Files.writeString(dir.resolve("forged.json"), forged)))
                .hasMessageContaining("not issued by Renova's vendor");

        assertThatThrownBy(() -> licences.install(Files.writeString(dir.resolve("old.json"), file("Acme", List.of("*"), TODAY.minusDays(1)))))
                .hasMessageContaining("expired");
        assertThatThrownBy(() -> licences.install(Files.writeString(dir.resolve("other.json"), "{\"hello\": 1}")))
                .isInstanceOf(LicenceException.class);
        assertThat(dir.resolve("licence.json")).doesNotExist();

        // A file put in place by hand is not trusted either.
        Files.writeString(dir.resolve("licence.json"), forged);
        assertThatThrownBy(() -> licences.requireMigration("java", "Java")).hasMessageContaining("cannot be used");
    }

    @Test
    void aDevelopmentBuildMigratesWithoutALicenceAndTheShippedKeyIsReadable(@TempDir Path dir) {
        assertThatCode(() -> new Licences(dir.resolve("none.json"), VENDOR.getPublic(), false).requireMigration("java", "Java"))
                .doesNotThrowAnyException();
        assertThat(Licences.version()).endsWith("-SNAPSHOT");
        assertThat(Licences.installed().enforced()).isEqualTo("true".equals(System.getenv("RENOVA_LICENCE_ENFORCE")));
        // The key that ships with Renova parses; without it no licence could ever be accepted.
        assertThatCode(() -> LicenceFile.publicKey(new String(
                Licences.class.getResourceAsStream("/renova-licence-key.pub").readAllBytes()))).doesNotThrowAnyException();
    }
}
