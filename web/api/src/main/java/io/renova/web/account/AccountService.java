package io.renova.web.account;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Accounts and organisations: first-run setup, sign-in, invitations and membership rules. New accounts are
 * created only by setup (the very first) or by accepting an invitation.
 */
@Service
public class AccountService {

    static final int MIN_PASSWORD = 10;
    static final Duration INVITATION_LIFETIME = Duration.ofDays(7);
    private static final int MAX_FAILURES = 5;
    private static final Duration LOCKOUT = Duration.ofMinutes(5);

    private final AccountStore store;
    private final PasswordEncoder passwords;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();
    /** Checked for unknown emails, so timing does not reveal which accounts exist. */
    private final String unknownUserHash;

    private record Failures(int count, Instant lockedUntil) {
    }

    /** A new invitation and its token, which is only available now. */
    public record Issued(Invitation invitation, String token) {
    }

    public AccountService(AccountStore store, PasswordEncoder passwords) {
        this.store = store;
        this.passwords = passwords;
        this.unknownUserHash = passwords.encode(UUID.randomUUID().toString());
    }

    public boolean setupRequired() {
        return store.users().isEmpty();
    }

    /** The first account and organisation. Only possible while no account exists. */
    public synchronized Organisation setup(String organisationName, String name, String email, String password) {
        if (!setupRequired()) {
            throw new IllegalStateException("Renova is already set up; ask an admin for an invitation");
        }
        User user = createUser(name, email, password);
        return createOrganisation(organisationName, user.id());
    }

    public Organisation createOrganisation(String name, String ownerId) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Give the organisation a name");
        }
        String now = Instant.now().toString();
        Organisation organisation = new Organisation(shortId(), name.strip(), now, List.of())
                .withMember(ownerId, Role.OWNER, now);
        store.saveOrganisation(organisation);
        return organisation;
    }

    /** The user, if the password is right; repeated failures lock the email for a few minutes. */
    public Optional<User> authenticate(String email, String password) {
        String key = AccountStore.normaliseEmail(email);
        Failures f = failures.get(key);
        if (f != null && f.lockedUntil() != null && Instant.now().isBefore(f.lockedUntil())) {
            throw new IllegalStateException("Too many failed sign-ins; try again in a few minutes");
        }
        Optional<User> user = store.userByEmail(key);
        boolean ok = passwords.matches(password == null ? "" : password, user.map(User::passwordHash).orElse(unknownUserHash));
        if (ok && user.isPresent()) {
            failures.remove(key);
            return user;
        }
        int count = (f == null ? 0 : f.count()) + 1;
        failures.put(key, new Failures(count, count >= MAX_FAILURES ? Instant.now().plus(LOCKOUT) : null));
        return Optional.empty();
    }

    public Issued invite(String organisationId, String email, Role role, String invitedBy) {
        String normalised = AccountStore.normaliseEmail(email);
        if (!normalised.matches("[^@\\s]+@[^@\\s]+")) {
            throw new IllegalArgumentException("Give a valid email address");
        }
        Organisation organisation = store.organisation(organisationId).orElseThrow();
        if (store.userByEmail(normalised).flatMap(u -> organisation.roleOf(u.id())).isPresent()) {
            throw new IllegalArgumentException(normalised + " is already a member");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = Instant.now();
        Invitation invitation = new Invitation(shortId(), organisationId, normalised, role, hash(token), invitedBy,
                now.toString(), now.plus(INVITATION_LIFETIME).toString());
        store.saveInvitation(invitation);
        return new Issued(invitation, token);
    }

    public Optional<Invitation> invitation(String token) {
        String h = hash(token == null ? "" : token);
        return store.invitations().stream()
                .filter(i -> MessageDigest.isEqual(i.tokenHash().getBytes(StandardCharsets.UTF_8), h.getBytes(StandardCharsets.UTF_8)))
                .filter(i -> Instant.now().isBefore(Instant.parse(i.expiresAt())))
                .findFirst();
    }

    /**
     * Joins the organisation: with a new account, or, when the email already has one, after checking its
     * password. The invitation is used up.
     */
    public synchronized User accept(String token, String name, String password) {
        Invitation invitation = invitation(token).orElseThrow(() -> new NoSuchElementException("This invitation is not valid or has expired"));
        User user = store.userByEmail(invitation.email()).orElse(null);
        if (user == null) {
            user = createUser(name, invitation.email(), password);
        } else if (authenticate(invitation.email(), password).isEmpty()) {
            throw new IllegalArgumentException("Wrong password for " + invitation.email());
        }
        String userId = user.id();
        store.updateOrganisation(invitation.organisationId(), o -> o.withMember(userId, invitation.role(), Instant.now().toString()));
        store.deleteInvitation(invitation.id());
        return user;
    }

    /** Changes a member's role. Only owners grant or take away the owner role, and one owner always remains. */
    public Organisation changeRole(String organisationId, Role callerRole, String userId, Role role) {
        return store.updateOrganisation(organisationId, o -> {
            Role current = o.roleOf(userId).orElseThrow(() -> new NoSuchElementException("Not a member"));
            if ((current == Role.OWNER || role == Role.OWNER) && callerRole != Role.OWNER) {
                throw new SecurityException("Only an owner can change owners");
            }
            if (current == Role.OWNER && role != Role.OWNER && o.owners() == 1) {
                throw new IllegalArgumentException("An organisation needs at least one owner");
            }
            return o.withMember(userId, role, Instant.now().toString());
        });
    }

    public Organisation removeMember(String organisationId, Role callerRole, String userId) {
        return store.updateOrganisation(organisationId, o -> {
            Role current = o.roleOf(userId).orElseThrow(() -> new NoSuchElementException("Not a member"));
            if (current == Role.OWNER && callerRole != Role.OWNER) {
                throw new SecurityException("Only an owner can remove an owner");
            }
            if (current == Role.OWNER && o.owners() == 1) {
                throw new IllegalArgumentException("An organisation needs at least one owner");
            }
            return o.without(userId);
        });
    }

    public void changePassword(String userId, String current, String replacement) {
        User user = store.user(userId).orElseThrow();
        if (!passwords.matches(current == null ? "" : current, user.passwordHash())) {
            throw new IllegalArgumentException("The current password is wrong");
        }
        checkPassword(replacement);
        store.saveUser(new User(user.id(), user.email(), user.name(), passwords.encode(replacement), user.createdAt()));
    }

    private User createUser(String name, String email, String password) {
        String normalised = AccountStore.normaliseEmail(email);
        if (!normalised.matches("[^@\\s]+@[^@\\s]+")) {
            throw new IllegalArgumentException("Give a valid email address");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Give your name");
        }
        if (store.userByEmail(normalised).isPresent()) {
            throw new IllegalArgumentException("An account for " + normalised + " already exists");
        }
        checkPassword(password);
        User user = new User(shortId(), normalised, name.strip(), passwords.encode(password), Instant.now().toString());
        store.saveUser(user);
        return user;
    }

    private static void checkPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD) {
            throw new IllegalArgumentException("Use a password of at least " + MIN_PASSWORD + " characters");
        }
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
