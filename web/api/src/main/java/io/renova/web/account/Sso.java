package io.renova.web.account;

import io.renova.web.audit.AuditLog;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Single sign-on with the organisation's identity provider over OpenID Connect (Microsoft Entra ID, Okta,
 * Google Workspace, Keycloak and any other that speaks it). On when {@code renova.sso.client-id} is set with
 * either {@code renova.sso.issuer} (the provider's address, from which the rest is discovered on first use) or
 * the endpoints themselves.
 *
 * <p>The provider says who someone is; Renova decides whether they get in. A person signs in if they have an
 * account, an invitation waiting for their email address, or an address in one of
 * {@code renova.sso.allowed-domains}, in which case they join the first organisation with
 * {@code renova.sso.role}. Roles are managed in Renova, not read from the provider.
 */
@Component
public class Sso {

    /** The one registration, and the last part of the addresses the provider is given. */
    public static final String ID = "sso";
    public static final String START = "/api/auth/sso";
    public static final String CALLBACK = "/api/auth/sso/callback";

    private final String issuer;
    private final String clientId;
    private final String clientSecret;
    private final String name;
    private final String authorizationUri;
    private final String tokenUri;
    private final String jwkSetUri;
    private final String userInfoUri;
    private final String publicUrl;
    private final List<String> allowedDomains;
    private final Role role;
    private final boolean only;
    private ClientRegistration registration;

    public Sso(@Value("${renova.sso.issuer:}") String issuer, @Value("${renova.sso.client-id:}") String clientId,
               @Value("${renova.sso.client-secret:}") String clientSecret, @Value("${renova.sso.name:single sign-on}") String name,
               @Value("${renova.sso.authorization-uri:}") String authorizationUri, @Value("${renova.sso.token-uri:}") String tokenUri,
               @Value("${renova.sso.jwk-set-uri:}") String jwkSetUri, @Value("${renova.sso.user-info-uri:}") String userInfoUri,
               @Value("${renova.public-url:http://localhost:3000}") String publicUrl,
               @Value("${renova.sso.allowed-domains:}") String allowedDomains, @Value("${renova.sso.role:member}") String role,
               @Value("${renova.sso.only:false}") boolean only) {
        this.issuer = issuer.strip();
        this.clientId = clientId.strip();
        this.clientSecret = clientSecret.strip();
        this.name = name.strip();
        this.authorizationUri = authorizationUri.strip();
        this.tokenUri = tokenUri.strip();
        this.jwkSetUri = jwkSetUri.strip();
        this.userInfoUri = userInfoUri.strip();
        this.publicUrl = publicUrl.strip().replaceAll("/+$", "");
        this.allowedDomains = Arrays.stream(allowedDomains.split(",")).map(d -> d.strip().toLowerCase(java.util.Locale.ROOT).replaceFirst("^@", ""))
                .filter(d -> !d.isEmpty()).toList();
        this.role = Role.valueOf(role.strip().toUpperCase(java.util.Locale.ROOT));
        if (this.role == Role.OWNER) {
            throw new IllegalArgumentException("renova.sso.role cannot be owner: owners are named by owners, in Renova");
        }
        this.only = only;
    }

    public boolean enabled() {
        return !clientId.isEmpty() && (!issuer.isEmpty() || !authorizationUri.isEmpty());
    }

    /** Whether passwords are refused, so that everyone signs in through the provider. */
    public boolean only() {
        return enabled() && only;
    }

    /** What the sign-in page shows: the provider's name and where the button leads. */
    public record View(String name, String url, boolean only) {
    }

    public View view() {
        return enabled() ? new View(name, START + "/" + ID, only) : null;
    }

    /** The provider's registration, discovered from the issuer the first time someone signs in, not at start-up. */
    public ClientRegistrationRepository registrations() {
        return id -> ID.equals(id) ? registration() : null;
    }

    private synchronized ClientRegistration registration() {
        if (registration == null) {
            ClientRegistration.Builder builder = authorizationUri.isEmpty()
                    ? ClientRegistrations.fromIssuerLocation(issuer).registrationId(ID)
                    : ClientRegistration.withRegistrationId(ID).authorizationUri(authorizationUri).tokenUri(tokenUri)
                            .jwkSetUri(jwkSetUri).userInfoUri(userInfoUri.isEmpty() ? null : userInfoUri)
                            .issuerUri(issuer.isEmpty() ? null : issuer).userNameAttributeName("sub")
                            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE);
            registration = builder.clientId(clientId).clientSecret(clientSecret).clientName(name)
                    .scope("openid", "email", "profile")
                    .redirectUri(publicUrl + CALLBACK + "/" + ID).build();
        }
        return registration;
    }

    /**
     * After the provider vouched for someone: find or make their account, and put the Renova user, not the
     * provider's, in the session. Someone Renova does not let in goes back to the sign-in page with the reason.
     */
    public AuthenticationSuccessHandler success(AccountService accounts, AccountStore store, SecurityContextRepository contexts,
                                                AuditLog audit) {
        return (request, response, authentication) -> {
            User user;
            try {
                OAuth2User provided = (OAuth2User) authentication.getPrincipal();
                if (Boolean.FALSE.equals(provided.getAttribute("email_verified"))) {
                    throw new SecurityException("The identity provider has not verified this email address");
                }
                String email = provided.getAttribute("email") != null ? provided.getAttribute("email") : provided.getAttribute("preferred_username");
                user = accounts.signInWithSso(email, provided.getAttribute("name"), allowedDomains, role);
            } catch (RuntimeException e) {
                SecurityContextHolder.clearContext();
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                response.sendRedirect(publicUrl + "/login?error=" + URLEncoder.encode(e.getMessage() == null ? "Sign-in failed" : e.getMessage(),
                        StandardCharsets.UTF_8));
                return;
            }
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user.id(), null,
                    AuthorityUtils.createAuthorityList("ROLE_USER")));
            SecurityContextHolder.setContext(context);
            contexts.saveContext(context, request, response);
            store.organisationsOf(user.id()).forEach(o -> audit.record(o.id(), user, "auth.signed_in", user.email(), "Through " + name));
            response.sendRedirect(publicUrl + "/");
        };
    }

    public AuthenticationFailureHandler failure() {
        return (request, response, exception) -> response.sendRedirect(publicUrl + "/login?error="
                + URLEncoder.encode("Sign-in through " + name + " did not complete: " + exception.getMessage(), StandardCharsets.UTF_8));
    }
}
