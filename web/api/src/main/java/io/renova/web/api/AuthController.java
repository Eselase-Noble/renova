package io.renova.web.api;

import io.renova.web.account.Access;
import io.renova.web.account.AccountService;
import io.renova.web.account.AccountStore;
import io.renova.web.account.Invitation;
import io.renova.web.account.Organisation;
import io.renova.web.account.Role;
import io.renova.web.account.User;
import io.renova.web.store.DataStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

/** Setup, sign-in and invitations: the only endpoints that work without a session. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AccountService accounts;
    private final AccountStore store;
    private final Access access;
    private final DataStore data;
    private final SecurityContextRepository contexts;

    public AuthController(AccountService accounts, AccountStore store, Access access, DataStore data,
                          SecurityContextRepository contexts) {
        this.accounts = accounts;
        this.store = store;
        this.access = access;
        this.data = data;
        this.contexts = contexts;
    }

    public record Membership(String id, String name, Role role) {
    }

    /** What the console needs to decide which page to show. */
    public record State(boolean setupRequired, User.View user, Membership organisation, List<Membership> organisations) {
    }

    public record Setup(String organisation, String name, String email, String password) {
    }

    public record Login(String email, String password) {
    }

    public record Accept(String name, String password) {
    }

    public record InvitationView(String organisation, String email, Role role, boolean accountExists, String expiresAt) {
    }

    public record SwitchOrganisation(String organisationId) {
    }

    public record PasswordChange(String current, String replacement) {
    }

    @GetMapping("/state")
    public State state(HttpServletRequest request) {
        if (access.user().isEmpty()) {
            return new State(accounts.setupRequired(), null, null, List.of());
        }
        Access.Caller caller = access.caller(request);
        List<Membership> mine = store.organisationsOf(caller.user().id()).stream()
                .map(o -> new Membership(o.id(), o.name(), o.roleOf(caller.user().id()).orElseThrow())).toList();
        return new State(false, caller.user().view(),
                new Membership(caller.organisationId(), caller.organisation().name(), caller.role()), mine);
    }

    @PostMapping("/setup")
    public State setup(@RequestBody Setup body, HttpServletRequest request, HttpServletResponse response) {
        Organisation organisation = accounts.setup(body.organisation(), body.name(), body.email(), body.password());
        // Projects and migrations from before accounts existed belong to the first organisation.
        data.adoptUnowned(organisation.id());
        signIn(store.userByEmail(body.email()).orElseThrow(), request, response);
        return state(request);
    }

    @PostMapping("/login")
    public State login(@RequestBody Login body, HttpServletRequest request, HttpServletResponse response) {
        User user = accounts.authenticate(body.email(), body.password())
                .orElseThrow(() -> new IllegalArgumentException("Wrong email or password"));
        signIn(user, request, response);
        return state(request);
    }

    @PostMapping("/logout")
    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    @GetMapping("/invitations/{token}")
    public InvitationView invitation(@PathVariable String token) {
        Invitation invitation = accounts.invitation(token)
                .orElseThrow(() -> new NoSuchElementException("This invitation is not valid or has expired"));
        String organisation = store.organisation(invitation.organisationId()).map(Organisation::name).orElse("");
        return new InvitationView(organisation, invitation.email(), invitation.role(),
                store.userByEmail(invitation.email()).isPresent(), invitation.expiresAt());
    }

    @PostMapping("/invitations/{token}/accept")
    public State accept(@PathVariable String token, @RequestBody Accept body, HttpServletRequest request,
                        HttpServletResponse response) {
        Invitation invitation = accounts.invitation(token)
                .orElseThrow(() -> new NoSuchElementException("This invitation is not valid or has expired"));
        User user = accounts.accept(token, body.name(), body.password());
        signIn(user, request, response);
        access.choose(request, invitation.organisationId());
        return state(request);
    }

    @PostMapping("/organisation")
    public State switchOrganisation(@RequestBody SwitchOrganisation body, HttpServletRequest request) {
        access.choose(request, body.organisationId());
        return state(request);
    }

    @PostMapping("/password")
    public void changePassword(@RequestBody PasswordChange body, HttpServletRequest request) {
        accounts.changePassword(access.caller(request).user().id(), body.current(), body.replacement());
    }

    /** A new session (against session fixation) holding the signed-in user. */
    private void signIn(User user, HttpServletRequest request, HttpServletResponse response) {
        HttpSession old = request.getSession(false);
        if (old != null) {
            old.invalidate();
        }
        request.getSession(true);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user.id(), null,
                AuthorityUtils.createAuthorityList("ROLE_USER")));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
    }
}
