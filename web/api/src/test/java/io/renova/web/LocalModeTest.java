package io.renova.web;

import io.renova.web.account.AccountStore;
import io.renova.web.account.LocalMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Local mode: one person on their own machine, no accounts, and no answers for anyone else. */
@SpringBootTest(properties = {"renova.mode=local", "server.address=127.0.0.1"})
@AutoConfigureMockMvc
class LocalModeTest {

    @TempDir
    static Path data;

    @Autowired
    MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("renova.data-dir", () -> data.resolve("local").toString());
        registry.add("renova.project-roots", () -> data.resolve("projects").toString());
    }

    @Test
    void everyRequestFromThisMachineIsTheOwnerWithoutSigningIn() throws Exception {
        mvc.perform(get("/api/auth/state"))
                .andExpect(jsonPath("$.localMode").value(true))
                .andExpect(jsonPath("$.setupRequired").value(false))
                .andExpect(jsonPath("$.organisation.name").value("This computer"))
                .andExpect(jsonPath("$.organisation.role").value("OWNER"));
        mvc.perform(get("/api/projects")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/system")).andExpect(jsonPath("$.localMode").value(true));

        Path project = Files.createDirectories(data.resolve("projects/legacy-app"));
        Files.writeString(project.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>legacy-app</artifactId>
                <version>1</version><packaging>war</packaging></project>
                """);
        String body = "{\"path\":\"" + project + "\"}";
        // Changes still need the CSRF token: a page on another site cannot make the browser add a project.
        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/projects").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
    }

    @Test
    void requestsNotAddressedToThisMachineAreRefused() throws Exception {
        // DNS rebinding: a host name someone else controls, resolving to 127.0.0.1.
        mvc.perform(get("/api/projects").header("Host", "attacker.example:8787")).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/state").header("Host", "attacker.example")).andExpect(status().isForbidden());
        mvc.perform(get("/api/projects").header("Host", "127.0.0.1:8787")).andExpect(status().isOk());
        mvc.perform(get("/api/projects").header("Host", "[::1]:8787")).andExpect(status().isOk());
    }

    @Test
    void thereAreNoAccountsToSignInToOrInvite() throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"local@localhost\",\"password\":\"anything at all\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/org/invitations").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"kofi@acme.test\",\"role\":\"MEMBER\"}")).andExpect(status().isConflict());
    }

    @Test
    void localModeRefusesToListenBeyondThisMachine(@TempDir Path dir) throws Exception {
        AccountStore store = new AccountStore(dir);
        assertThatThrownBy(() -> new LocalMode("local", "0.0.0.0", store)).hasMessageContaining("server.address=127.0.0.1");
        assertThatThrownBy(() -> new LocalMode("local", "", store)).isInstanceOf(IllegalStateException.class);
        assertThat(new LocalMode("server", "0.0.0.0", store).enabled()).isFalse();
    }
}
