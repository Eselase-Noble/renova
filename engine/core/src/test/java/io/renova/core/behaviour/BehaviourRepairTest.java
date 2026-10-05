package io.renova.core.behaviour;

import io.renova.core.engine.BuildError;
import io.renova.core.engine.VerifyResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BehaviourRepairTest {

    private static final List<Route> ROUTES = List.of(
            new Route(null, "/claims/{id}", "web/src/main/java/ClaimsController.java"),
            new Route("POST", "/claims", "web/src/main/java/ClaimsController.java"),
            new Route("GET", "/claims/export", "web/src/main/java/ExportController.java"),
            new Route(null, "/orders.jsp", "web/src/main/webapp/orders.jsp"));

    @Test
    void routesMatchByMethodAndLiteralSegments() {
        assertThat(ROUTES.get(0).match("GET", "/claims/7")).isEqualTo(1);
        assertThat(ROUTES.get(0).match("GET", "/claims/${claim}?full=1")).isEqualTo(1);
        assertThat(ROUTES.get(1).match("GET", "/claims")).isEqualTo(-1);
        assertThat(ROUTES.get(2).match("GET", "/claims/export")).isEqualTo(2);
        assertThat(ROUTES.get(0).match("GET", "/claims/7/items")).isEqualTo(-1);
    }

    @Test
    void stepsAreSentToTheMostSpecificHandler() {
        Scenario file = new Scenario("file.claim", "renova-scenarios.yaml", List.of(
                new Step("POST", "/claims", null, null, false, null, null),
                Step.get("/claims/${claim}"),
                Step.get("/claims/export"),
                Step.get("/unknown")));
        assertThat(BehaviourVerifier.handler(file, 0, ROUTES)).isEqualTo("web/src/main/java/ClaimsController.java");
        assertThat(BehaviourVerifier.handler(file, 1, ROUTES)).isEqualTo("web/src/main/java/ClaimsController.java");
        assertThat(BehaviourVerifier.handler(file, 2, ROUTES)).isEqualTo("web/src/main/java/ExportController.java");
        assertThat(BehaviourVerifier.handler(file, 3, ROUTES)).isNull();
        // A request found in the code keeps the handler discovery gave it (e.g. the routing configuration).
        Scenario slash = new Scenario("s3", "GET", "/claims/1/", "ClaimsController#show, with a trailing slash",
                "web/src/main/webapp/WEB-INF/spring/servlet-context.xml");
        assertThat(BehaviourVerifier.handler(slash, 0, ROUTES)).isEqualTo("web/src/main/webapp/WEB-INF/spring/servlet-context.xml");
    }

    @Test
    void differencesBecomeErrorsOnTheHandlingFiles() {
        Scenario page = new Scenario("s2", "GET", "/orders.jsp", "JSP page", "web/src/main/webapp/orders.jsp");
        Exchange before = new Exchange(200, Map.of("content-type", "text/html;charset=ISO-8859-1"),
                "<html><body>Orders</body></html>".getBytes(StandardCharsets.UTF_8), null);
        Exchange after = new Exchange(200, Map.of("content-type", "text/html;charset=UTF-8"), before.body(), null);
        ScenarioResult differs = ResponseComparator.compare(page, before, before, after).withHandler(page.handlerFile());

        Scenario post = new Scenario("file.claim", "renova-scenarios.yaml", List.of(new Step("POST", "/claims", null, null, false, null, null)));
        ScenarioResult posted = new ScenarioResult(post, 0, before, before, List.of(), List.of(), "web/src/main/java/ClaimsController.java");

        BehaviourReport report = new BehaviourReport(BehaviourReport.Status.DIFFERENT, "2 differences", "Java 8, Tomcat 9",
                "Java 21, Tomcat 10.1", List.of(differs, posted),
                Map.of("file.claim", List.of("table claims: the original added 1 row(s); the migrated app added no rows")),
                List.of(), null, null);

        List<BuildError> errors = BehaviourErrors.of(report);
        assertThat(errors).extracting(BuildError::file)
                .containsExactly("web/src/main/webapp/orders.jsp", "web/src/main/java/ClaimsController.java");
        assertThat(errors.get(0).message())
                .startsWith("behaviour differs from the original application (original on Java 8, Tomcat 9, migrated on Java 21, Tomcat 10.1)"
                        + " for GET /orders.jsp (from JSP page): Content-Type \"text/html;charset=iso-8859-1\" became \"text/html;charset=utf-8\"")
                .contains("The original answered: \"<html><body>Orders</body></html>\"");
        assertThat(errors.get(1).message()).contains("scenario file.claim changed the database differently: table claims");
    }

    @Test
    void checkingVerifierReportsBuildErrorsFirstThenBehaviour() throws Exception {
        BehaviourReport same = new BehaviourReport(BehaviourReport.Status.SAME, "all the same", null, null, List.of(), Map.of(),
                List.of(), null, null);
        BehaviourReport differs = new BehaviourReport(BehaviourReport.Status.DIFFERENT, "the app did not start", "a", "b",
                List.of(), Map.of(), List.of(), null, null);
        VerifyResult broken = new VerifyResult(false, List.of(new BuildError("A.java", 3, "cannot find symbol")), "");
        VerifyResult built = new VerifyResult(true, List.of(), "");

        assertThat(new BehaviourCheckingVerifier(c -> broken, c -> same).verify(null)).isSameAs(broken);
        BehaviourCheckingVerifier passing = new BehaviourCheckingVerifier(c -> built, c -> same);
        assertThat(passing.verify(null).success()).isTrue();
        assertThat(passing.last()).isSameAs(same);
        VerifyResult failing = new BehaviourCheckingVerifier(c -> built, c -> differs).verify(null);
        assertThat(failing.success()).isFalse();
        assertThat(failing.errors()).singleElement().satisfies(e -> {
            assertThat(e.file()).isNull();
            assertThat(e.message()).contains("the app did not start");
        });
    }
}
