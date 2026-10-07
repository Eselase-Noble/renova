package io.renova.web;

import io.renova.web.account.AccountService;
import io.renova.web.account.AccountStore;
import io.renova.web.account.LocalMode;
import io.renova.web.account.Sso;
import io.renova.web.audit.AuditLog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * Sessions with an HttpOnly cookie, CSRF protection in the form single-page apps use (an XSRF-TOKEN cookie
 * the console echoes in an X-XSRF-TOKEN header), and everything under /api except signing in needs a
 * signed-in user. With {@code renova.sso.*} set, people also sign in through the organisation's identity
 * provider: see {@link Sso}. In local mode ({@code renova.mode=local}) there is no sign-in: see {@link LocalMode}. Roles within an organisation are checked by {@link io.renova.web.account.Access}.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, SecurityContextRepository contexts, LocalMode local, Sso sso,
                            AccountService accounts, AccountStore store, AuditLog audit) throws Exception {
        if (local.enabled()) {
            // No accounts: each request is the machine's one user. See LocalMode for what keeps that safe.
            http.addFilterBefore(local.filter(), AuthorizationFilter.class);
        }
        if (sso.enabled() && !local.enabled()) {
            // The organisation's identity provider says who someone is; Sso decides whether they get in.
            http.oauth2Login(login -> login.clientRegistrationRepository(sso.registrations())
                    .authorizationEndpoint(endpoint -> endpoint.baseUri(Sso.START))
                    .redirectionEndpoint(endpoint -> endpoint.baseUri(Sso.CALLBACK + "/*"))
                    .successHandler(sso.success(accounts, store, contexts, audit))
                    .failureHandler(sso.failure()));
        }
        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/state", "/api/auth/setup", "/api/auth/login", "/api/auth/logout",
                                "/api/auth/invitations/**", Sso.START + "/**").permitAll()
                        .anyRequest().authenticated())
                .csrf(csrf -> csrf.spa())
                .securityContext(context -> context.securityContextRepository(contexts))
                .exceptionHandling(e -> e.authenticationEntryPoint((request, response, ex) -> {
                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write("{\"error\":\"Sign in first\"}");
                }))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable());
        return http.build();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
