package io.renova.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The REST API with accounts, over a temporary data directory and tiny Maven projects. The tests run in
 * order: setup, then a second user invited as a viewer, then isolation between organisations.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebApiTest {

    @TempDir
    static Path data;
    static Path projects;
    static MockHttpSession owner;
    static String projectId;

    @Autowired
    MockMvc mvc;
    final ObjectMapper json = new ObjectMapper();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("renova.data-dir", () -> data.resolve("server").toString());
        registry.add("renova.project-roots", () -> data.resolve("projects").toString());
    }

    @BeforeAll
    static void projects() throws Exception {
        projects = Files.createDirectories(data.resolve("projects"));
        for (String name : new String[] {"legacy-app", "other-app"}) {
            Path p = Files.createDirectories(projects.resolve(name));
            Files.writeString(p.resolve("pom.xml"), """
                    <project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>%s</artifactId>
                    <version>1</version><packaging>war</packaging>
                    <properties><maven.compiler.source>1.8</maven.compiler.source></properties></project>
                    """.formatted(name));
        }
        Files.createDirectories(data.resolve("outside"));
        Files.writeString(data.resolve("outside/pom.xml"), "<project/>");
    }

    @Test
    @Order(1)
    void firstRunSetsUpTheFirstAccountAndOrganisation() throws Exception {
        mvc.perform(get("/api/auth/state")).andExpect(jsonPath("$.setupRequired").value(true));
        mvc.perform(get("/api/projects")).andExpect(status().isUnauthorized());
        // Without the CSRF token, nothing that changes state is accepted.
        mvc.perform(post("/api/auth/setup").contentType(MediaType.APPLICATION_JSON).content(setup("Acme", "ama@acme.test")))
                .andExpect(status().isForbidden());

        owner = new MockHttpSession();
        call(post("/api/auth/setup"), owner, setup("Acme", "Ama@Acme.test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value("ama@acme.test"))
                .andExpect(jsonPath("$.organisation.role").value("OWNER"));
        owner = signIn("ama@acme.test", "correct horse battery");

        call(post("/api/auth/setup"), new MockHttpSession(), setup("Again", "eve@acme.test")).andExpect(status().isConflict());
        call(post("/api/auth/login"), new MockHttpSession(), "{\"email\":\"ama@acme.test\",\"password\":\"wrong password!\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(2)
    void ownersAddProjectsOnlyUnderTheAllowedRoots() throws Exception {
        String created = call(post("/api/projects"), owner, "{\"path\":\"" + projects.resolve("legacy-app") + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.playbook").value("java8-to-21-jakarta-ee10"))
                .andReturn().getResponse().getContentAsString();
        projectId = json.readTree(created).get("id").asText();
        call(post("/api/projects"), owner, "{\"path\":\"" + data.resolve("outside") + "\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(containsString("renova.project-roots")));
        mvc.perform(get("/api/projects/" + projectId + "/assessment").session(owner))
                .andExpect(jsonPath("$.schema").value("renova/report/v1"));
    }

    @Test
    @Order(3)
    void anInvitedViewerSeesButCannotChange() throws Exception {
        String invitation = call(post("/api/org/invitations"), owner, "{\"email\":\"kofi@acme.test\",\"role\":\"VIEWER\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String token = json.readTree(invitation).get("token").asText();
        mvc.perform(get("/api/org/invitations").session(owner)).andExpect(jsonPath("$[0].token").doesNotExist());

        mvc.perform(get("/api/auth/invitations/" + token))
                .andExpect(jsonPath("$.organisation").value("Acme"))
                .andExpect(jsonPath("$.accountExists").value(false));
        call(post("/api/auth/invitations/" + token + "/accept"), new MockHttpSession(),
                "{\"name\":\"Kofi\",\"password\":\"kofi's long password\"}")
                .andExpect(jsonPath("$.organisation.role").value("VIEWER"));
        // Used up.
        mvc.perform(get("/api/auth/invitations/" + token)).andExpect(status().isNotFound());

        MockHttpSession viewer = signIn("kofi@acme.test", "kofi's long password");
        mvc.perform(get("/api/projects").session(viewer)).andExpect(jsonPath("$", hasSize(1)));
        call(post("/api/projects/" + projectId + "/migrations"), viewer, "{}").andExpect(status().isForbidden());
        call(put("/api/settings/keys/anthropic"), viewer, "{\"apiKey\":\"sk-ant-x\"}").andExpect(status().isForbidden());

        // The owner promotes Kofi; an admin still cannot touch owners.
        JsonNode members = json.readTree(mvc.perform(get("/api/org").session(owner)).andReturn().getResponse().getContentAsString());
        String kofi = members.get("members").findValuesAsText("id").get(1);
        String ama = members.get("members").findValuesAsText("id").get(0);
        call(patch("/api/org/members/" + kofi), owner, "{\"role\":\"ADMIN\"}").andExpect(jsonPath("$.members", hasSize(2)));
        call(patch("/api/org/members/" + ama), viewer, "{\"role\":\"MEMBER\"}").andExpect(status().isForbidden());
        call(delete("/api/org/members/" + ama), owner, null).andExpect(status().isBadRequest());
    }

    @Test
    @Order(4)
    void migrationsCanBeCancelledAndChangesAreAudited() throws Exception {
        // One migration runs at a time, so the second is still queued when it is cancelled.
        String first = json.readTree(call(post("/api/projects/" + projectId + "/migrations"), owner, "{\"skipTests\":true}")
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).get("id").asText();
        String second = json.readTree(call(post("/api/projects/" + projectId + "/migrations"), owner, "{\"skipTests\":true}")
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).get("id").asText();
        call(post("/api/migrations/" + second + "/cancel"), owner, null).andExpect(status().isOk());
        call(post("/api/migrations/" + first + "/cancel"), owner, null).andExpect(status().isOk());
        for (String id : new String[] {second, first}) {
            String state = "";
            for (int i = 0; i < 300 && !state.equals("CANCELLED"); i++) {
                Thread.sleep(100);
                state = json.readTree(mvc.perform(get("/api/migrations/" + id).session(owner)).andReturn().getResponse()
                        .getContentAsString()).at("/migration/status").asText();
            }
            assertThat(state).as("migration " + id).isEqualTo("CANCELLED");
        }
        call(post("/api/migrations/" + second + "/cancel"), owner, null).andExpect(status().isConflict());

        String log = mvc.perform(get("/api/org/audit").session(owner)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(log).findValuesAsText("action"))
                .contains("organisation.created", "project.added", "invitation.created", "invitation.accepted",
                        "member.role_changed", "migration.started", "migration.cancelled", "auth.signed_in");
        assertThat(log).doesNotContain("correct horse battery");
        mvc.perform(get("/api/org/audit?area=migration").session(owner)).andExpect(jsonPath("$", hasSize(4)));

        mvc.perform(get("/api/system").session(owner))
                .andExpect(jsonPath("$.ecosystems[0].id").value("java"))
                .andExpect(jsonPath("$.projectRoots", hasSize(1)));
        mvc.perform(get("/api/system/directories").session(owner)).andExpect(jsonPath("$.entries", hasSize(1)));
        mvc.perform(get("/api/system/directories").param("path", projects.toString()).session(owner))
                .andExpect(jsonPath("$.entries", hasSize(2)))
                .andExpect(jsonPath("$.entries[0].name").value("legacy-app"))
                .andExpect(jsonPath("$.entries[0].project").value(true))
                .andExpect(jsonPath("$.parent").doesNotExist());
        mvc.perform(get("/api/system/directories").param("path", data.resolve("outside").toString()).session(owner))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(5)
    void organisationsAreIsolatedAndKeysStayMasked() throws Exception {
        String secret = "sk-ant-test-0123456789abcdefWXYZ";
        String saved = call(put("/api/settings/keys/anthropic"), owner, "{\"apiKey\":\"" + secret + "\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(saved).doesNotContain(secret).contains("WXYZ");
        assertThat(Files.readString(data.resolve("server/accounts/settings").toFile().listFiles()[0].toPath()))
                .doesNotContain(secret);

        // A second organisation of the same owner sees none of the first one's projects or keys.
        call(post("/api/orgs"), owner, "{\"name\":\"Side project\"}").andExpect(status().isCreated());
        mvc.perform(get("/api/projects").session(owner)).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/projects/" + projectId).session(owner)).andExpect(status().isNotFound());
        mvc.perform(get("/api/settings").session(owner)).andExpect(jsonPath("$.providers[0].keyConfigured").value(false));
        call(post("/api/projects/" + projectId + "/migrations"), owner, "{\"ai\":true}").andExpect(status().isNotFound());
    }

    private String setup(String organisation, String email) {
        return "{\"organisation\":\"" + organisation + "\",\"name\":\"Ama\",\"email\":\"" + email
                + "\",\"password\":\"correct horse battery\"}";
    }

    private MockHttpSession signIn(String email, String password) throws Exception {
        MockHttpSession session = new MockHttpSession();
        return (MockHttpSession) call(post("/api/auth/login"), session,
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .andExpect(status().isOk()).andReturn().getRequest().getSession();
    }

    private ResultActions call(MockHttpServletRequestBuilder request, MockHttpSession session, String body) throws Exception {
        request.session(session).with(csrf());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }
}
