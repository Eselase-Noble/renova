package io.renova.web.api;

import io.renova.web.account.Access;
import io.renova.web.account.AccountService;
import io.renova.web.account.AccountStore;
import io.renova.web.account.Invitation;
import io.renova.web.account.Organisation;
import io.renova.web.account.Role;
import io.renova.web.account.User;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

/** The current organisation: its members, their roles and invitations. */
@RestController
@RequestMapping("/api")
public class OrganisationController {

    private final Access access;
    private final AccountService accounts;
    private final AccountStore store;

    public OrganisationController(Access access, AccountService accounts, AccountStore store) {
        this.access = access;
        this.accounts = accounts;
        this.store = store;
    }

    public record MemberView(String id, String email, String name, Role role, String joinedAt) {
    }

    public record OrganisationView(String id, String name, Role yourRole, List<MemberView> members) {
    }

    public record NewOrganisation(String name) {
    }

    public record NewInvitation(String email, Role role) {
    }

    public record RoleChange(Role role) {
    }

    /** @param token only returned when the invitation is created; build the link from it */
    public record InvitationView(String id, String email, Role role, String createdAt, String expiresAt, String token) {
        static InvitationView of(Invitation i, String token) {
            return new InvitationView(i.id(), i.email(), i.role(), i.createdAt(), i.expiresAt(), token);
        }
    }

    /** Any signed-in user may start another organisation, which they own. */
    @PostMapping("/orgs")
    @ResponseStatus(HttpStatus.CREATED)
    public OrganisationView create(@RequestBody NewOrganisation body, HttpServletRequest request) {
        Organisation organisation = accounts.createOrganisation(body.name(), access.caller(request).user().id());
        access.choose(request, organisation.id());
        return view(access.caller(request));
    }

    @GetMapping("/org")
    public OrganisationView current(HttpServletRequest request) {
        return view(access.caller(request));
    }

    @PatchMapping("/org/members/{userId}")
    public OrganisationView changeRole(@PathVariable String userId, @RequestBody RoleChange body, HttpServletRequest request) {
        Access.Caller caller = access.require(request, Role.ADMIN);
        if (body.role() == null) {
            throw new IllegalArgumentException("Give a role");
        }
        accounts.changeRole(caller.organisationId(), caller.role(), userId, body.role());
        return view(access.caller(request));
    }

    @DeleteMapping("/org/members/{userId}")
    public OrganisationView remove(@PathVariable String userId, HttpServletRequest request) {
        Access.Caller caller = access.caller(request);
        if (!userId.equals(caller.user().id())) {
            caller.require(Role.ADMIN);
        }
        accounts.removeMember(caller.organisationId(), caller.role(), userId);
        return userId.equals(caller.user().id()) ? null : view(access.caller(request));
    }

    @GetMapping("/org/invitations")
    public List<InvitationView> invitations(HttpServletRequest request) {
        Access.Caller caller = access.require(request, Role.ADMIN);
        return store.invitations().stream().filter(i -> i.organisationId().equals(caller.organisationId()))
                .map(i -> InvitationView.of(i, null)).toList();
    }

    @PostMapping("/org/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationView invite(@RequestBody NewInvitation body, HttpServletRequest request) {
        Access.Caller caller = access.require(request, Role.ADMIN);
        Role role = body.role() == null ? Role.MEMBER : body.role();
        if (role == Role.OWNER && caller.role() != Role.OWNER) {
            throw new SecurityException("Only an owner can invite an owner");
        }
        AccountService.Issued issued = accounts.invite(caller.organisationId(), body.email(), role, caller.user().id());
        return InvitationView.of(issued.invitation(), issued.token());
    }

    @DeleteMapping("/org/invitations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable String id, HttpServletRequest request) {
        Access.Caller caller = access.require(request, Role.ADMIN);
        Invitation invitation = store.invitations().stream()
                .filter(i -> i.id().equals(id) && i.organisationId().equals(caller.organisationId())).findFirst()
                .orElseThrow(() -> new NoSuchElementException("No invitation " + id));
        store.deleteInvitation(invitation.id());
    }

    private OrganisationView view(Access.Caller caller) {
        Organisation o = caller.organisation();
        List<MemberView> members = store.organisation(o.id()).orElseThrow().members().stream().map(m -> {
            User u = store.user(m.userId()).orElse(null);
            return new MemberView(m.userId(), u == null ? "" : u.email(), u == null ? "(removed account)" : u.name(), m.role(),
                    m.joinedAt());
        }).toList();
        return new OrganisationView(o.id(), o.name(), caller.role(), members);
    }
}
