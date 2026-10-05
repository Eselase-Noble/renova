package io.renova.core.behaviour;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BehaviourComparisonTest {

    private static final Scenario ITEMS = new Scenario("s1", "GET", "/items", "ItemController#list");

    private static Exchange html(int status, String body) {
        return new Exchange(status, Map.of("content-type", "text/html;charset=UTF-8"), body.getBytes(StandardCharsets.UTF_8), null);
    }

    @Test
    void sameStatusAndNormalisedBodyIsTheSame() {
        Exchange before = html(200, "<p>Order  placed 2024-05-01T10:15:00Z, ref 0f8fad5b-d9cb-469f-a165-70867728950e</p>");
        Exchange again = html(200, "<p>Order placed 2024-05-01T10:15:03Z, ref 7c9e6679-7425-40de-944b-e07fc1f90ae7</p>");
        Exchange after = html(200, "<p>Order placed\n2024-06-02T08:00:00Z, ref 16fd2706-8baf-433b-82eb-8c7fada847da</p>");
        assertThat(ResponseComparator.compare(ITEMS, before, again, after).same()).isTrue();
    }

    @Test
    void reportsStatusRedirectContentTypeAndBodyDifferences() {
        assertThat(ResponseComparator.compare(ITEMS, html(200, "x"), html(200, "x"), html(404, "")).differences())
                .containsExactly("status 200 became 404");

        Exchange redirect = new Exchange(302, Map.of("location", "http://baseline:8080/login;jsessionid=AB12"), null, null);
        Exchange moved = new Exchange(302, Map.of("location", "http://candidate:8080/signin"), null, null);
        assertThat(ResponseComparator.compare(ITEMS, redirect, redirect, moved).differences())
                .containsExactly("redirect to /login became /signin");

        Exchange latin = new Exchange(200, Map.of("content-type", "text/html;charset=ISO-8859-1"), "x".getBytes(), null);
        assertThat(ResponseComparator.compare(ITEMS, html(200, "x"), html(200, "x"), latin).differences())
                .containsExactly("Content-Type \"text/html;charset=utf-8\" became \"text/html;charset=iso-8859-1\"");

        assertThat(ResponseComparator.compare(ITEMS, html(200, "<td>Jack</td><td>4</td>"), html(200, "<td>Jack</td><td>4</td>"),
                html(200, "<td>Jack</td><td></td>")).differences()).singleElement().asString()
                .isEqualTo("body differs at character 17: \"<td>Jack</td><td>4</td>\" became \"<td>Jack</td><td></td>\"");
    }

    @Test
    void errorPagesAreComparedByStatusAndVaryingBodiesAreNotCompared() {
        // Container error pages differ between Tomcat versions by design.
        assertThat(ResponseComparator.compare(ITEMS, html(500, "Tomcat 9 page"), html(500, "Tomcat 9 page"),
                html(500, "Tomcat 10.1 page")).same()).isTrue();

        ScenarioResult varying = ResponseComparator.compare(ITEMS, html(200, "visits: 1"), html(200, "visits: 2"), html(200, "visits: 9"));
        assertThat(varying.same()).isTrue();
        assertThat(varying.notes()).singleElement().asString().contains("changes between identical requests");
    }

    @Test
    void jsonIsComparedStructurally() {
        Exchange before = new Exchange(200, Map.of("content-type", "application/json"),
                "{\"sku\":\"A-1\",\"qty\":4}".getBytes(), null);
        Exchange after = new Exchange(200, Map.of("content-type", "application/json"),
                "{ \"qty\": 4,\n  \"sku\": \"A-1\" }".getBytes(), null);
        Exchange changed = new Exchange(200, Map.of("content-type", "application/json"),
                "{\"sku\":\"A-1\",\"qty\":\"4\"}".getBytes(), null);
        assertThat(ResponseComparator.compare(ITEMS, before, before, after).same()).isTrue();
        assertThat(ResponseComparator.compare(ITEMS, before, before, changed).same()).isFalse();
    }

    @Test
    void parsesProbeOutput() {
        String body = Base64.getEncoder().encodeToString("<p>hi</p>".getBytes());
        DockerSandbox.Run run = DockerSandbox.parse(List.of(
                "@@READY baseline ok 4", "@@READY candidate ok 6",
                "@@ s1 baseline 1 200", "H Content-Type: text/html", "B " + body,
                "@@ s1 candidate 1 -1", "E Connection refused",
                "@@ s1 baseline 2 200", "H Content-Type: text/html", "B " + body));
        assertThat(run.baselineReady()).isTrue();
        assertThat(run.candidateReady()).isTrue();
        DockerSandbox.Answers answers = run.answers().get("s1");
        assertThat(answers.baseline().text()).isEqualTo("<p>hi</p>");
        assertThat(answers.baseline().header("Content-Type")).isEqualTo("text/html");
        assertThat(answers.baselineAgain().status()).isEqualTo(200);
        assertThat(answers.candidate().responded()).isFalse();
        assertThat(answers.candidate().error()).isEqualTo("Connection refused");
    }
}
