package io.renova.web;

import io.renova.web.account.AccountService;
import io.renova.web.account.AccountStore;
import io.renova.web.account.Role;
import io.renova.web.account.User;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Single sign-on: what the sign-in page is told, where the button leads, and who Renova lets in once the
 * identity provider has said who they are.
 */
@SpringBootTest(properties = {
        "renova.sso.name=Acme ID", "renova.sso.client-id=renova", "renova.sso.client-secret=s3cret",
        "renova.sso.authorization-uri=https://id.acme.test/authorize", "renova.sso.token-uri=https://id.acme.test/token",
        "renova.sso.jwk-set-uri=https://id.acme.test/keys", "renova.sso.allowed-domains=acme.test, @partner.test",
        "renova.sso.role=viewer", "renova.sso.only=true", "renova.public-url=https://renova.acme.test/"})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SsoTest {

    @TempDir
    static Path data;

    @Autowired
    MockMvc mvc;
    @Autowired
    AccountService accounts;
    @Autowired
    AccountStore store;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("renova.data-dir", () -> data.resolve("server").toString());
        registry.add("renova.project-roots", () -> data.resolve("projects").toString());
    }

    @Test
    @Order(1)
    void nobodyGetsInThroughTheProviderBeforeRenovaIsSetUp() {
        assertThatThrownBy(() -> accounts.signInWithSso("kofi@acme.test", "Kofi", List.of("acme.test"), Role.MEMBER))
                .isInstanceOf(SecurityException.class).hasMessageContaining("not set up yet");
    }

    @Test
    @Order(2)
    void theSignInPageIsToldAboutTheProviderAndTheButtonLeadsToIt() throws Exception {
        mvc.perform(get("/api/auth/state"))
                .andExpect(jsonPath("$.sso.name").value("Acme ID"))
                .andExpect(jsonPath("$.sso.url").value("/api/auth/sso/sso"))
                .andExpect(jsonPath("$.sso.only").value(true));
        mvc.perform(get("/api/auth/sso/sso"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", allOf(startsWith("https://id.acme.test/authorize?"), containsString("client_id=renova"),
                        containsString("scope=openid%20email%20profile"),
                        containsString("redirect_uri=https://renova.acme.test/api/auth/sso/callback/sso"))));
        // Everything else still needs a session.
        mvc.perform(get("/api/projects")).andExpect(status().isUnauthorized());
    }

    @Test
    @Order(3)
    void peopleGetInWithAnAccountAnInvitationOrAnAllowedDomain() {
        accounts.setup("Acme", "Ama", "ama@acme.test", "correct horse battery");
        String acme = store.organisations().getFirst().id();

        // An account: signed in as that account, whatever case the provider writes the address in.
        assertThat(accounts.signInWithSso("Ama@Acme.test", "Ama Owusu", List.of(), Role.VIEWER).id())
                .isEqualTo(store.userByEmail("ama@acme.test").orElseThrow().id());

        // An allowed domain: a new account, in the first organisation, with the role single sign-on gives.
        User kofi = accounts.signInWithSso("kofi@acme.test", "Kofi Boateng", List.of("acme.test"), Role.VIEWER);
        assertThat(kofi.name()).isEqualTo("Kofi Boateng");
        assertThat(store.organisation(acme).orElseThrow().roleOf(kofi.id())).contains(Role.VIEWER);
        // Its password is nobody's: the account cannot be signed in to without the provider.
        assertThat(accounts.authenticate("kofi@acme.test", "")).isEmpty();

        // An invitation: the role the admin chose, and the invitation is used up.
        accounts.invite(acme, "esi@elsewhere.test", Role.ADMIN, kofi.id());
        User esi = accounts.signInWithSso("esi@elsewhere.test", null, List.of("acme.test"), Role.VIEWER);
        assertThat(esi.name()).isEqualTo("esi");
        assertThat(store.organisation(acme).orElseThrow().roleOf(esi.id())).contains(Role.ADMIN);
        assertThat(store.invitations()).isEmpty();

        // Anyone else the provider knows is not let in, and no account is made for them.
        assertThatThrownBy(() -> accounts.signInWithSso("yaw@elsewhere.test", "Yaw", List.of("acme.test"), Role.VIEWER))
                .isInstanceOf(SecurityException.class).hasMessageContaining("ask an admin");
        assertThat(store.userByEmail("yaw@elsewhere.test")).isEmpty();
        assertThatThrownBy(() -> accounts.signInWithSso(null, "Nobody", List.of("acme.test"), Role.VIEWER))
                .isInstanceOf(SecurityException.class).hasMessageContaining("no email address");
    }

    @Test
    @Order(4)
    void passwordsAreRefusedWhereEveryoneSignsInThroughTheProvider() throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ama@acme.test\",\"password\":\"correct horse battery\"}"))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.error").value(containsString("sign in with Acme ID")));
    }
}
