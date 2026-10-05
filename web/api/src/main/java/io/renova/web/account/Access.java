package io.renova.web.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Who is calling and in which organisation. The organisation is the one chosen in this session, or the
 * caller's first; every project, migration and setting a request touches must belong to it.
 */
@Component
public class Access {

    static final String ORGANISATION = "renova.organisation";

    private final AccountStore store;

    public Access(AccountStore store) {
        this.store = store;
    }

    /** The signed-in user, their current organisation and their role in it. */
    public record Caller(User user, Organisation organisation, Role role) {

        public Caller require(Role needed) {
            if (!role.atLeast(needed)) {
                throw new SecurityException("This needs the " + needed.name().toLowerCase() + " role in " + organisation.name());
            }
            return this;
        }

        public String organisationId() {
            return organisation.id();
        }
    }

    public Caller caller(HttpServletRequest request) {
        User user = user().orElseThrow(() -> new SecurityException("Sign in first"));
        List<Organisation> mine = store.organisationsOf(user.id());
        if (mine.isEmpty()) {
            throw new SecurityException("You are not a member of any organisation");
        }
        HttpSession session = request.getSession();
        Object chosen = session.getAttribute(ORGANISATION);
        Organisation organisation = mine.stream().filter(o -> o.id().equals(chosen)).findFirst().orElse(mine.getFirst());
        session.setAttribute(ORGANISATION, organisation.id());
        return new Caller(user, organisation, organisation.roleOf(user.id()).orElseThrow());
    }

    public Caller require(HttpServletRequest request, Role role) {
        return caller(request).require(role);
    }

    public void choose(HttpServletRequest request, String organisationId) {
        User user = user().orElseThrow(() -> new SecurityException("Sign in first"));
        Organisation organisation = store.organisation(organisationId)
                .filter(o -> o.roleOf(user.id()).isPresent())
                .orElseThrow(() -> new NoSuchElementException("No organisation " + organisationId));
        request.getSession().setAttribute(ORGANISATION, organisation.id());
    }

    public java.util.Optional<User> user() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof String userId)) {
            return java.util.Optional.empty();
        }
        return store.user(userId);
    }
}
