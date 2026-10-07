package io.renova.core.licence;

import io.renova.core.config.UserConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Properties;

/**
 * Where the licence is kept on this machine, and what it allows. Assessing a project is free; migrating one
 * needs a licence that covers its ecosystem and has not expired. Everything is checked locally.
 *
 * <p>The licence is the file named by {@code RENOVA_LICENCE} (for servers and build agents), else
 * {@code licence.json} beside Renova's user configuration. A development build (a version ending in
 * {@code -SNAPSHOT}) does not ask for one; {@code RENOVA_LICENCE_ENFORCE=true} makes it.
 */
public final class Licences {

    /** What is installed: a valid licence, a problem with the one that is there, or nothing. */
    public record Status(Optional<Licence> licence, String problem, Path file) {

        public boolean valid() {
            return licence.isPresent() && problem == null;
        }

        public String describe() {
            return valid() ? licence.get().describe() : problem != null ? problem : "No licence installed";
        }
    }

    private final Path file;
    private final PublicKey vendor;
    private final boolean enforced;

    public Licences(Path file, PublicKey vendor, boolean enforced) {
        this.file = file;
        this.vendor = vendor;
        this.enforced = enforced;
    }

    /** The licence of this machine's user, checked against the vendor key shipped with Renova. */
    public static Licences installed() {
        String named = System.getenv("RENOVA_LICENCE");
        Path file = named != null && !named.isBlank() ? Path.of(named)
                : UserConfig.defaultLocation().file().resolveSibling("licence.json");
        String enforce = System.getenv("RENOVA_LICENCE_ENFORCE");
        boolean enforced = enforce != null && !enforce.isBlank() ? Boolean.parseBoolean(enforce) : !version().endsWith("-SNAPSHOT");
        return new Licences(file, vendorKey(), enforced);
    }

    public boolean enforced() {
        return enforced;
    }

    public Path file() {
        return file;
    }

    public Status status() {
        return status(LocalDate.now());
    }

    Status status(LocalDate today) {
        if (!Files.isRegularFile(file)) {
            return new Status(Optional.empty(), null, file);
        }
        try {
            Licence licence = read(Files.readString(file, StandardCharsets.UTF_8));
            return new Status(Optional.of(licence), licence.expired(today)
                    ? "The licence of " + licence.licensee() + " expired on " + licence.expires() : null, file);
        } catch (LicenceException | IOException e) {
            return new Status(Optional.empty(), e.getMessage(), file);
        }
    }

    /** Checks a licence file and makes it this machine's licence; an invalid or expired one is refused. */
    public Licence install(Path source) throws IOException {
        String content = Files.readString(source, StandardCharsets.UTF_8);
        Licence licence = read(content);
        if (licence.expired(LocalDate.now())) {
            throw new LicenceException("This licence expired on " + licence.expires() + ".");
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return licence;
    }

    /** Throws {@link LicenceException}, saying what to do, unless migrating a project of this ecosystem is allowed. */
    public void requireMigration(String ecosystem, String ecosystemName) {
        if (!enforced) {
            return;
        }
        Status status = status();
        String how = " Assessing projects stays free. Install a licence with \"renova licence install FILE\" or in the desktop "
                + "app's settings.";
        if (status.licence().isEmpty()) {
            throw new LicenceException((status.problem() == null ? "Migrating needs a Renova licence, and none is installed."
                    : "The licence in " + file + " cannot be used: " + status.problem()) + how);
        }
        if (status.problem() != null) {
            throw new LicenceException(status.problem() + ". Migrating needs a current licence." + how);
        }
        if (!status.licence().get().covers(ecosystem)) {
            throw new LicenceException("The licence of " + status.licence().get().licensee() + " covers "
                    + String.join(", ", status.licence().get().ecosystems()) + ", not " + ecosystemName + "." + how);
        }
    }

    private Licence read(String content) {
        if (vendor == null) {
            throw new LicenceException("This build of Renova has no vendor key to check licences with.");
        }
        return LicenceFile.read(content, vendor);
    }

    /** The version this build was made as, from the build itself. */
    public static String version() {
        try (InputStream in = Licences.class.getResourceAsStream("/renova-build.properties")) {
            Properties build = new Properties();
            if (in != null) {
                build.load(in);
            }
            return build.getProperty("version", "0-SNAPSHOT");
        } catch (IOException e) {
            return "0-SNAPSHOT";
        }
    }

    private static PublicKey vendorKey() {
        try (InputStream in = Licences.class.getResourceAsStream("/renova-licence-key.pub")) {
            return in == null ? null : LicenceFile.publicKey(new String(in.readAllBytes(), StandardCharsets.US_ASCII));
        } catch (IOException | IllegalStateException e) {
            return null;
        }
    }
}
