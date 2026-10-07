package io.renova.java.behaviour;

import io.renova.core.model.ProjectModel;
import io.renova.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Which application of a project is run side by side, and how; nothing here needs Docker. */
class JavaBehaviourRunnerTest {

    private static final String BOOT = "<parent><groupId>org.springframework.boot</groupId>"
            + "<artifactId>spring-boot-starter-parent</artifactId><version>2.7.18</version></parent>";
    private final JavaBehaviourRunner runner = new JavaBehaviourRunner();

    @Test
    void aSpringBootApplicationRunsByItself(@TempDir Path root) throws Exception {
        write(root, "pom.xml", pom(BOOT + "<artifactId>orders</artifactId><properties><java.version>11</java.version></properties>"));
        write(root, "src/main/java/com/acme/OrdersApplication.java", "@SpringBootApplication\npublic class OrdersApplication {}\n");
        write(root, "src/main/resources/application.properties", "spring.jpa.open-in-view=false\nserver.servlet.context-path = /orders/\n");
        ProjectModel model = new JavaPlugin().model(root);

        assertThat(runner.unsupported(model)).isEmpty();
        assertThat(JavaBehaviourRunner.application(model).springBoot()).isTrue();
        assertThat(JavaBehaviourRunner.contextPath(root)).isEqualTo("/orders");
        // Settings reach a JVM started with java -jar through the variable every JVM reads.
        assertThat(runner.environment(model, Map.of("db.url", "jdbc:x"))).isEqualTo(Map.of("JAVA_TOOL_OPTIONS", "-Ddb.url=jdbc:x"));
    }

    @Test
    void aWarWithoutSpringBootStillRunsOnTheServletContainer(@TempDir Path root) throws Exception {
        write(root, "pom.xml", pom("<groupId>g</groupId><artifactId>shop</artifactId><version>1</version><packaging>war</packaging>"));
        ProjectModel model = new JavaPlugin().model(root);

        assertThat(runner.unsupported(model)).isEmpty();
        assertThat(JavaBehaviourRunner.application(model).springBoot()).isFalse();
        assertThat(runner.environment(model, Map.of("db.url", "jdbc:x"))).isEqualTo(Map.of("CATALINA_OPTS", "-Ddb.url=jdbc:x"));
    }

    @Test
    void aLibraryOrSeveralApplicationsAreNotRun(@TempDir Path root) throws Exception {
        Path library = root.resolve("library");
        write(library, "pom.xml", pom(BOOT + "<artifactId>starter</artifactId>"));
        write(library, "src/main/java/com/acme/Helper.java", "public class Helper {}\n");
        assertThat(runner.unsupported(new JavaPlugin().model(library))).get().asString().contains("no web application to run");

        Path services = root.resolve("services");
        write(services, "pom.xml", pom(BOOT + "<artifactId>services</artifactId><packaging>pom</packaging>"
                + "<modules><module>a</module><module>b</module></modules>"));
        for (String name : new String[] {"a", "b"}) {
            write(services, name + "/pom.xml", pom("<parent><groupId>org.springframework.boot</groupId>"
                    + "<artifactId>services</artifactId><version>1</version></parent><artifactId>" + name + "</artifactId>"));
            write(services, name + "/src/main/java/App.java", "@SpringBootApplication\npublic class App {}\n");
        }
        assertThat(runner.unsupported(new JavaPlugin().model(services))).get().asString().contains("several Spring Boot applications");
    }

    @Test
    void readsTheContextPathFromYamlAndPicksARuntimeForTheJavaLevel(@TempDir Path root) throws Exception {
        write(root, "src/main/resources/application.yml", "server:\n  port: 9090\n  servlet:\n    context-path: \"/api\"\n");
        assertThat(JavaBehaviourRunner.contextPath(root)).isEqualTo("/api");
        assertThat(JavaBehaviourRunner.contextPath(root.resolve("none"))).isEmpty();

        assertThat(JavaBehaviourRunner.runtimeRelease("1.8")).isEqualTo(8);
        assertThat(JavaBehaviourRunner.runtimeRelease("6")).isEqualTo(8);
        assertThat(JavaBehaviourRunner.runtimeRelease("11")).isEqualTo(11);
        assertThat(JavaBehaviourRunner.runtimeRelease("16")).isEqualTo(17);
        assertThat(JavaBehaviourRunner.runtimeRelease("21")).isEqualTo(21);
    }

    private static String pom(String body) {
        return "<project><modelVersion>4.0.0</modelVersion>" + body + "</project>";
    }

    private static void write(Path root, String file, String content) throws Exception {
        Path target = root.resolve(file);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }
}
