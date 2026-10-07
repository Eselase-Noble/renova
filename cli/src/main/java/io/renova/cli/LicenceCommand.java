package io.renova.cli;

import io.renova.core.licence.Licence;
import io.renova.core.licence.LicenceException;
import io.renova.core.licence.LicenceFile;
import io.renova.core.licence.Licences;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "licence", aliases = "license", description = "Show or install this machine's Renova licence.",
        subcommands = {LicenceCommand.Show.class, LicenceCommand.Install.class, LicenceCommand.Keygen.class, LicenceCommand.Issue.class})
final class LicenceCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        return new Show().call();
    }

    @Command(name = "show", description = "Show the installed licence and what it allows.")
    static final class Show implements Callable<Integer> {
        @Override
        public Integer call() {
            Licences licences = Licences.installed();
            Licences.Status status = licences.status();
            System.out.println("Licence: " + status.describe());
            System.out.println("File:    " + licences.file() + (status.licence().isPresent() || status.problem() != null ? "" : " (not there)"));
            System.out.println(licences.enforced()
                    ? "Assessing projects is free; migrating needs a licence that covers the project's ecosystem."
                    : "This is a development build (" + Licences.version() + "): it migrates without a licence.");
            return status.valid() || !licences.enforced() ? 0 : 1;
        }
    }

    @Command(name = "install", description = "Check a licence file and make it this machine's licence.")
    static final class Install implements Callable<Integer> {
        @Parameters(paramLabel = "FILE", description = "The licence file you were sent.")
        Path file;

        @Override
        public Integer call() throws Exception {
            try {
                Licence licence = Licences.installed().install(file);
                System.out.println("Installed: " + licence.describe());
                return 0;
            } catch (LicenceException e) {
                System.err.println(e.getMessage());
                return 1;
            }
        }
    }

    /** For the vendor: makes the key pair licences are signed and checked with. Done once. */
    @Command(name = "keygen", hidden = true, description = "Vendor: create the key pair licences are signed with.")
    static final class Keygen implements Callable<Integer> {
        @Option(names = "--out", required = true, paramLabel = "DIR", description = "Where to write licence-signing.key and licence-signing.pub.")
        Path out;

        @Override
        public Integer call() throws Exception {
            Path key = out.resolve("licence-signing.key");
            if (Files.exists(key)) {
                System.err.println(key + " exists. Licences signed with it stop working if it is replaced; move it away first.");
                return 1;
            }
            Files.createDirectories(out);
            KeyPair pair = LicenceFile.newKeyPair();
            Files.writeString(key, LicenceFile.encode(pair.getPrivate()) + "\n", StandardCharsets.US_ASCII);
            try {
                Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException e) {
                // Windows: the user's profile folder is already private to them.
            }
            Files.writeString(out.resolve("licence-signing.pub"), LicenceFile.encode(pair.getPublic()) + "\n", StandardCharsets.US_ASCII);
            System.out.println("Private key (keep it secret, back it up): " + key);
            System.out.println("Public key (ships with Renova):           " + out.resolve("licence-signing.pub"));
            return 0;
        }
    }

    /** For the vendor: writes a licence file for a customer. */
    @Command(name = "issue", hidden = true, description = "Vendor: write a signed licence file for a customer.")
    static final class Issue implements Callable<Integer> {
        @Option(names = "--key", required = true, paramLabel = "FILE", description = "The private signing key.")
        Path key;
        @Option(names = "--licensee", required = true, description = "Who the licence is for.")
        String licensee;
        @Option(names = "--ecosystems", split = ",", defaultValue = "*", description = "java, dotnet, ... or * for all. Default: *.")
        List<String> ecosystems;
        @Option(names = "--edition", defaultValue = "standard", description = "A name for what was sold, e.g. trial, team.")
        String edition;
        @Option(names = "--seats", defaultValue = "1", description = "How many people may use it.")
        int seats;
        @Option(names = "--days", description = "Valid for this many days from today.")
        Integer days;
        @Option(names = "--expires", paramLabel = "YYYY-MM-DD", description = "Or: the last day it is valid.")
        LocalDate expires;
        @Option(names = "--id", description = "Your reference. Default: one made from the date and the licensee.")
        String id;
        @Option(names = {"-o", "--out"}, required = true, paramLabel = "FILE", description = "The licence file to write.")
        Path out;

        @Override
        public Integer call() throws Exception {
            if ((days == null) == (expires == null)) {
                System.err.println("Give --days or --expires, one of them.");
                return 2;
            }
            LocalDate today = LocalDate.now();
            LocalDate until = expires != null ? expires : today.plusDays(days);
            String reference = id != null ? id : "RNV-" + today.toString().replace("-", "") + "-"
                    + Integer.toHexString(licensee.toLowerCase(java.util.Locale.ROOT).hashCode() & 0xffff).toUpperCase(java.util.Locale.ROOT);
            Licence licence = new Licence(reference, licensee, edition, ecosystems, seats, today, until);
            Files.writeString(out, LicenceFile.write(licence, LicenceFile.privateKey(Files.readString(key))), StandardCharsets.UTF_8);
            System.out.println("Wrote " + out + ": " + licence.describe() + " [" + reference + "]");
            return 0;
        }
    }
}
