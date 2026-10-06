package io.renova.web.account;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Renova for one person on their own machine ({@code renova.mode=local}): no accounts and no sign-in. Every
 * request is the machine's one user, in the one organisation, as its owner.
 *
 * <p>That is only safe while nobody else can reach the API, so local mode refuses to start unless the server
 * listens on a loopback address, and it answers only requests addressed to this machine by name: a web page
 * elsewhere that points its own host name at 127.0.0.1 (DNS rebinding) is turned away. Requests that change
 * something still need the CSRF token, as in server mode.
 */
@Component
public class LocalMode {

    /** Requests a browser on this machine sends: localhost, 127.x.x.x or [::1], with or without a port. */
    private static final Pattern LOCAL_HOST = Pattern.compile("^(localhost|127(\\.\\d{1,3}){3}|\\[::1])(:\\d+)?$", Pattern.CASE_INSENSITIVE);
    static final String EMAIL = "local@localhost";

    private final boolean enabled;
    private final AccountStore store;
    private volatile String userId;

    public LocalMode(@Value("${renova.mode:server}") String mode, @Value("${server.address:}") String address, AccountStore store)
            throws IOException {
        this.enabled = "local".equalsIgnoreCase(mode);
        this.store = store;
        if (enabled) {
            if (address.isBlank() || !InetAddress.getByName(address).isLoopbackAddress()) {
                throw new IllegalStateException("renova.mode=local has no sign-in, so the API must listen only on this machine: set "
                        + "server.address=127.0.0.1 (it is '" + address + "'). To serve other people, use server mode with accounts.");
            }
            this.userId = ensureUser().id();
        }
    }

    public boolean enabled() {
        return enabled;
    }

    /** The machine's one user and organisation, created the first time local mode starts on a data directory. */
    private User ensureUser() {
        User user = store.userByEmail(EMAIL).orElseGet(() -> {
            String name = System.getProperty("user.name", "You");
            // No password can match this: the account is only ever used through local mode.
            User created = new User(shortId(), EMAIL, name, "!local-" + UUID.randomUUID(), Instant.now().toString());
            store.saveUser(created);
            return created;
        });
        if (store.organisationsOf(user.id()).isEmpty()) {
            String now = Instant.now().toString();
            store.saveOrganisation(new Organisation(shortId(), "This computer", now, List.of()).withMember(user.id(), Role.OWNER, now));
        }
        return user;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Signs every request in as the local user, after checking it is addressed to this machine. */
    public OncePerRequestFilter filter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                // HTTP/1.1 always sends Host; without it, the name the container resolved the request to.
                String host = request.getHeader("Host") == null ? request.getServerName() : request.getHeader("Host");
                if (host == null || !LOCAL_HOST.matcher(host).matches()) {
                    response.setStatus(HttpStatus.FORBIDDEN.value());
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write("{\"error\":\"Renova runs in local mode and only answers this machine\"}");
                    return;
                }
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(userId, null,
                        AuthorityUtils.createAuthorityList("ROLE_USER")));
                SecurityContextHolder.setContext(context);
                try {
                    chain.doFilter(request, response);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }
        };
    }
}
