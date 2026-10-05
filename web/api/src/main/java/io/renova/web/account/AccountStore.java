package io.renova.web.account;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** Users, organisations and invitations as JSON files in the data directory. */
@Component
public class AccountStore {

    private final Path users;
    private final Path organisations;
    private final Path invitations;
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public AccountStore(@Value("${renova.data-dir}") Path dataDir) throws IOException {
        Path dir = Files.createDirectories(dataDir.toAbsolutePath().normalize().resolve("accounts"));
        this.users = dir.resolve("users.json");
        this.organisations = dir.resolve("organisations.json");
        this.invitations = dir.resolve("invitations.json");
    }

    public synchronized List<User> users() {
        return read(users, new TypeReference<>() { });
    }

    public Optional<User> user(String id) {
        return users().stream().filter(u -> u.id().equals(id)).findFirst();
    }

    public Optional<User> userByEmail(String email) {
        String wanted = normaliseEmail(email);
        return users().stream().filter(u -> u.email().equals(wanted)).findFirst();
    }

    public synchronized void saveUser(User user) {
        List<User> all = new ArrayList<>(users());
        all.removeIf(u -> u.id().equals(user.id()));
        all.add(user);
        write(users, all);
    }

    public synchronized List<Organisation> organisations() {
        return read(organisations, new TypeReference<>() { });
    }

    public Optional<Organisation> organisation(String id) {
        return organisations().stream().filter(o -> o.id().equals(id)).findFirst();
    }

    public List<Organisation> organisationsOf(String userId) {
        return organisations().stream().filter(o -> o.roleOf(userId).isPresent()).toList();
    }

    public synchronized void saveOrganisation(Organisation organisation) {
        List<Organisation> all = new ArrayList<>(organisations());
        all.removeIf(o -> o.id().equals(organisation.id()));
        all.add(organisation);
        write(organisations, all);
    }

    /** Reads, changes and saves an organisation in one step, so concurrent changes are not lost. */
    public synchronized Organisation updateOrganisation(String id, UnaryOperator<Organisation> change) {
        Organisation updated = change.apply(organisation(id).orElseThrow());
        saveOrganisation(updated);
        return updated;
    }

    public synchronized List<Invitation> invitations() {
        return read(invitations, new TypeReference<>() { });
    }

    public synchronized void saveInvitation(Invitation invitation) {
        List<Invitation> all = new ArrayList<>(invitations());
        all.removeIf(i -> i.id().equals(invitation.id()));
        all.add(invitation);
        write(invitations, all);
    }

    public synchronized boolean deleteInvitation(String id) {
        List<Invitation> all = new ArrayList<>(invitations());
        boolean removed = all.removeIf(i -> i.id().equals(id));
        write(invitations, all);
        return removed;
    }

    public static String normaliseEmail(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }

    private <T> List<T> read(Path file, TypeReference<List<T>> type) {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            return json.readValue(file.toFile(), type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(Path file, Object value) {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            json.writeValue(tmp.toFile(), value);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
