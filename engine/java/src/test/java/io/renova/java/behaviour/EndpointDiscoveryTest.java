package io.renova.java.behaviour;

import io.renova.core.behaviour.Route;
import io.renova.core.behaviour.Scenario;
import io.renova.core.model.ProjectModel;
import io.renova.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class EndpointDiscoveryTest {

    @Test
    void findsSpringRoutesServletsAndPagesUnderTheDispatcherMapping(@TempDir Path root) throws Exception {
        write(root, "pom.xml", "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>shop</artifactId>"
                + "<version>1</version><packaging>war</packaging></project>");
        write(root, "src/main/webapp/WEB-INF/web.xml", """
                <web-app>
                    <servlet><servlet-name>app</servlet-name>
                        <servlet-class>org.springframework.web.servlet.DispatcherServlet</servlet-class></servlet>
                    <servlet-mapping><servlet-name>app</servlet-name><url-pattern>/app/*</url-pattern></servlet-mapping>
                    <servlet><servlet-name>health</servlet-name><servlet-class>com.acme.Health</servlet-class></servlet>
                    <servlet-mapping><servlet-name>health</servlet-name><url-pattern>/health</url-pattern></servlet-mapping>
                </web-app>
                """);
        write(root, "src/main/webapp/index.jsp", "hi");
        write(root, "src/main/java/com/acme/Health.java", "package com.acme;\npublic class Health {}\n");
        write(root, "src/main/webapp/WEB-INF/views/orders.jsp", "not directly reachable");
        write(root, "src/main/java/com/acme/OrderController.java", """
                package com.acme;

                @Controller
                @RequestMapping("/orders")
                public class OrderController {
                    @RequestMapping(method = RequestMethod.GET)
                    public String list() { return "orders"; }

                    @GetMapping(value = {"/{orderId}", "/by-ref/{ref}"}, produces = "text/html")
                    public String show(@PathVariable long orderId) { return "order"; }

                    @RequestMapping(value = "/{id}", method = RequestMethod.POST)
                    public String update() { return "redirect:/orders"; }
                }
                """);
        ProjectModel model = new JavaPlugin().model(root);

        JavaBehaviourRunner runner = new JavaBehaviourRunner();
        assertThat(runner.unsupported(model)).isEmpty();
        assertThat(runner.discover(model, root)).extracting(s -> s.steps().getFirst().path(), Scenario::why).containsExactly(
                tuple("/", "the application root"),
                tuple("/health", "web.xml servlet health"),
                tuple("/app/orders", "OrderController#list (Spring @RequestMapping)"),
                tuple("/app/orders/", "OrderController#list (Spring @RequestMapping), with a trailing slash"),
                tuple("/app/orders/1", "OrderController#show (Spring @GetMapping)"),
                tuple("/app/orders/1/", "OrderController#show (Spring @GetMapping), with a trailing slash"),
                tuple("/app/orders/by-ref/sample", "OrderController#show (Spring @GetMapping)"),
                tuple("/app/orders/by-ref/sample/", "OrderController#show (Spring @GetMapping), with a trailing slash"),
                tuple("/index.jsp", "JSP page"));

        // Each request knows the file to fix: the controller, the JSP, or for URL matching the configuration.
        assertThat(runner.discover(model, root)).extracting(Scenario::handlerFile).containsExactly(
                "src/main/webapp/WEB-INF/web.xml", "src/main/java/com/acme/Health.java",
                "src/main/java/com/acme/OrderController.java", "src/main/webapp/WEB-INF/web.xml",
                "src/main/java/com/acme/OrderController.java", "src/main/webapp/WEB-INF/web.xml",
                "src/main/java/com/acme/OrderController.java", "src/main/webapp/WEB-INF/web.xml",
                "src/main/webapp/index.jsp");
        assertThat(runner.routes(model, root)).extracting(Route::method, Route::template).contains(
                tuple(null, "/health"), tuple("GET", "/app/orders"), tuple("POST", "/app/orders/{id}"),
                tuple("GET", "/app/orders/{orderId}"), tuple(null, "/index.jsp"));
    }

    @Test
    void explainsWhatCannotBeRunYet(@TempDir Path root) throws Exception {
        write(root, "pom.xml", "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>ledger</artifactId>"
                + "<version>1</version><packaging>war</packaging><dependencies><dependency><groupId>javax</groupId>"
                + "<artifactId>javaee-api</artifactId><version>7.0</version><scope>provided</scope></dependency></dependencies></project>");
        assertThat(new JavaBehaviourRunner().unsupported(new JavaPlugin().model(root))).get().asString()
                .startsWith("needs a Jakarta EE server");
    }

    private static void write(Path root, String path, String content) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
