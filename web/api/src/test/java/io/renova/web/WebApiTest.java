package io.renova.web;

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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The REST API over a temporary data directory and a tiny Maven project. No Maven run or AI call. */
@SpringBootTest
@AutoConfigureMockMvc
class WebApiTest {

    @TempDir
    static Path data;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("renova.data-dir", () -> data.resolve("server").toString());
        registry.add("renova.user-config", () -> data.resolve("config.properties").toString());
    }

    @Autowired
    MockMvc mvc;

    @Test
    void addsAProjectAndAssessesIt() throws Exception {
        Path project = Files.createDirectories(data.resolve("legacy-app"));
        Files.writeString(project.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>legacy-app</artifactId>
                <version>1</version><packaging>war</packaging>
                <properties><maven.compiler.source>1.8</maven.compiler.source></properties>
                <dependencies><dependency><groupId>javax.servlet</groupId><artifactId>javax.servlet-api</artifactId>
                <version>3.1.0</version><scope>provided</scope></dependency></dependencies></project>
                """);

        String created = mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"" + project + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("legacy-app"))
                .andExpect(jsonPath("$.playbook").value("java8-to-21-jakarta-ee10"))
                .andReturn().getResponse().getContentAsString();
        String id = created.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mvc.perform(get("/api/projects")).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/projects/" + id + "/assessment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schema").value("renova/report/v1"))
                .andExpect(jsonPath("$.plan[0].rule").exists());
        mvc.perform(get("/api/projects/" + id + "/migrations")).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void explainsWhatIsWrong() throws Exception {
        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{\"path\":\"/no/such/dir\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("No such directory")));
        mvc.perform(get("/api/projects/nope")).andExpect(status().isNotFound());
        mvc.perform(get("/api/migrations/..%2F..%2Fetc")).andExpect(status().isNotFound());
        mvc.perform(get("/api/playbooks")).andExpect(jsonPath("$[0].id").value("java8-to-21-jakarta-ee10"));
    }

    @Test
    void refusesAnAiMigrationWithoutAProviderAndNeverShowsKeys() throws Exception {
        Path project = Files.createDirectories(data.resolve("other-app"));
        Files.writeString(project.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId>"
                + "<artifactId>other-app</artifactId><version>1</version></project>");
        String created = mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"" + project + "\"}")).andReturn().getResponse().getContentAsString();
        String id = created.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        mvc.perform(post("/api/projects/" + id + "/migrations").contentType(MediaType.APPLICATION_JSON).content("{\"ai\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("No AI provider is configured")));

        String secret = "sk-ant-test-0123456789abcdefWXYZ";
        String saved = mvc.perform(put("/api/settings/keys/anthropic").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"" + secret + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers[?(@.name == 'anthropic')].keyConfigured").value(true))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(saved).doesNotContain(secret).contains("WXYZ");
        org.assertj.core.api.Assertions.assertThat(mvc.perform(get("/api/settings")).andReturn().getResponse()
                .getContentAsString()).doesNotContain(secret);
        mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON).content("{\"anthropic.apiKey\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }
}
