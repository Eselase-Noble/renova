package io.renova.java.fix;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpringBootReplatformerTest {

    @TempDir
    Path module;

    private void file(String path, String content) throws Exception {
        Path file = module.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private String read(String path) throws Exception {
        return Files.readString(module.resolve(path));
    }

    @Test
    void sessionBeansAndScopesBecomeSpringComponents() {
        String bean = """
                package com.acme.pay;

                import jakarta.ejb.EJB;
                import jakarta.ejb.Stateless;
                import jakarta.ejb.TransactionAttribute;
                import jakarta.ejb.TransactionAttributeType;
                import jakarta.persistence.EntityManager;
                import jakarta.persistence.PersistenceContext;

                @Stateless(name = "Payments")
                public class Payments {

                    @PersistenceContext(unitName = "pay")
                    private EntityManager em;

                    @EJB
                    private Ledger ledger;

                    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
                    public void audit() {
                    }

                    @TransactionAttribute
                    public void pay() {
                    }
                }
                """;
        String converted = SpringBootReplatformer.components(bean, true);
        assertThat(converted).contains("@Service\n@Transactional\npublic class Payments", "    @PersistenceContext\n",
                "    @Autowired\n    private Ledger ledger;", "    @Transactional(propagation = Propagation.REQUIRES_NEW)\n",
                "    @Transactional\n    public void pay()", "import org.springframework.stereotype.Service;",
                "import org.springframework.transaction.annotation.Propagation;",
                "import org.springframework.beans.factory.annotation.Autowired;");
        assertThat(converted).doesNotContain("jakarta.ejb", "@Stateless", "@EJB");
        // With several persistence units the name says which one is meant.
        assertThat(SpringBootReplatformer.components(bean, false)).contains("@PersistenceContext(unitName = \"pay\")");
    }

    @Test
    void scopesKeepTheirMeaning() {
        String scoped = "package a;\n\nimport jakarta.enterprise.context.RequestScoped;\n\n@RequestScoped\npublic class Basket {\n}\n";
        assertThat(SpringBootReplatformer.components(scoped, true)).contains("@Component\n@RequestScope\npublic class Basket",
                "import org.springframework.web.context.annotation.RequestScope;");
        // A class that only shares a simple name with an EJB annotation is not touched.
        String other = "package a;\n\nimport com.other.Stateless;\n\n@Stateless\npublic class Thing {\n}\n";
        assertThat(SpringBootReplatformer.components(other, true)).isEqualTo(other);
    }

    @Test
    void anApplicationMovesFromItsServerToSpringBoot() throws Exception {
        file("pom.xml", """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.acme</groupId>
                    <artifactId>pay</artifactId>
                    <version>1</version>
                    <packaging>war</packaging>

                    <dependencies>
                        <dependency>
                            <groupId>jakarta.platform</groupId>
                            <artifactId>jakarta.jakartaee-api</artifactId>
                            <version>10.0.0</version>
                            <scope>provided</scope>
                        </dependency>
                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-lang3</artifactId>
                            <version>3.14.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        file("src/main/java/com/acme/pay/api/RestApp.java", "package com.acme.pay.api;\n\nimport jakarta.ws.rs.ApplicationPath;\n"
                + "import jakarta.ws.rs.core.Application;\n\n@ApplicationPath(\"/api\")\npublic class RestApp extends Application {\n}\n");
        file("src/main/java/com/acme/pay/api/PayResource.java", "package com.acme.pay.api;\n\nimport jakarta.ws.rs.GET;\n"
                + "import jakarta.ws.rs.Path;\n\n@Path(\"/pay\")\npublic class PayResource {\n    @GET\n    public String get() {\n        return \"ok\";\n    }\n}\n");
        file("src/main/java/com/acme/pay/model/Payment.java", "package com.acme.pay.model;\n\nimport jakarta.persistence.Entity;\n"
                + "import jakarta.persistence.Id;\n\n@Entity\npublic class Payment {\n    @Id\n    Long id;\n}\n");
        file("src/main/resources/META-INF/persistence.xml", """
                <persistence><persistence-unit name="pay">
                  <jta-data-source>java:/PayDS</jta-data-source>
                  <properties>
                    <property name="hibernate.hbm2ddl.auto" value="validate"/>
                    <property name="hibernate.jdbc.batch_size" value="20"/>
                  </properties>
                </persistence-unit></persistence>
                """);
        file("src/main/webapp/WEB-INF/beans.xml", "<beans/>");
        file("src/main/webapp/WEB-INF/jboss-web.xml", "<jboss-web><context-root>payments</context-root></jboss-web>");

        List<String> notes = new ArrayList<>();
        assertThat(SpringBootReplatformer.convert(module, "3.5.7", notes)).isTrue();

        assertThat(read("src/main/java/com/acme/pay/Application.java")).contains("package com.acme.pay;", "@SpringBootApplication",
                "SpringApplication.run(Application.class, args)").doesNotContain("SpringBootServletInitializer");
        assertThat(read("src/main/java/com/acme/pay/api/RestApp.java")).contains("@ApplicationPath(\"/api\")",
                "extends ResourceConfig", "register(com.acme.pay.api.PayResource.class);");
        assertThat(read("src/main/java/com/acme/pay/api/PayResource.java")).contains("@Component\n@Path(\"/pay\")");
        assertThat(read("src/main/resources/application.properties")).contains("spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.properties.hibernate.jdbc.batch_size=20", "server.servlet.context-path=/payments", "#spring.datasource.url=");
        assertThat(module.resolve("src/main/resources/META-INF/persistence.xml")).doesNotExist();
        assertThat(module.resolve("src/main/webapp")).doesNotExist();
        assertThat(read("src/test/java/com/acme/pay/ApplicationStartsTest.java")).contains("@SpringBootTest(properties", "jdbc:h2:mem");
        String pom = read("pom.xml");
        assertThat(pom).contains("<packaging>jar</packaging>", "spring-boot-dependencies", "spring-boot-starter-jersey",
                "spring-boot-starter-data-jpa", "spring-boot-maven-plugin", "<goal>repackage</goal>", "commons-lang3")
                .doesNotContain("jakartaee-api");
        // Done once: the application is a Spring Boot application now.
        assertThat(SpringBootReplatformer.convert(module, "3.5.7", new ArrayList<>())).isFalse();
    }

    @Test
    void anApplicationWithPagesStaysAWarThatAlsoRunsOnItsOwn() throws Exception {
        file("pom.xml", "<project>\n    <modelVersion>4.0.0</modelVersion>\n    <groupId>g</groupId>\n    <artifactId>shop</artifactId>\n"
                + "    <version>1</version>\n    <packaging>war</packaging>\n</project>\n");
        file("src/main/java/com/acme/shop/Hello.java", "package com.acme.shop;\n\nimport jakarta.servlet.annotation.WebServlet;\n"
                + "import jakarta.servlet.http.HttpServlet;\n\n@WebServlet(\"/hello\")\npublic class Hello extends HttpServlet {\n}\n");
        file("src/main/java/com/acme/shop/Report.java", "package com.acme.shop;\n\nimport jakarta.servlet.http.HttpServlet;\n\n"
                + "public class Report extends HttpServlet {\n}\n");
        file("src/main/webapp/index.jsp", "<html/>");
        file("src/main/webapp/WEB-INF/web.xml", """
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0">
                  <context-param><param-name>region</param-name><param-value>gh</param-value></context-param>
                  <filter><filter-name>audit</filter-name><filter-class>com.acme.shop.AuditFilter</filter-class></filter>
                  <filter-mapping><filter-name>audit</filter-name><url-pattern>/*</url-pattern></filter-mapping>
                  <servlet>
                    <servlet-name>monthly-report</servlet-name>
                    <servlet-class>com.acme.shop.Report</servlet-class>
                    <init-param><param-name>format</param-name><param-value>pdf</param-value></init-param>
                    <load-on-startup>1</load-on-startup>
                  </servlet>
                  <servlet-mapping><servlet-name>monthly-report</servlet-name><url-pattern>/report</url-pattern><url-pattern>/report/*</url-pattern></servlet-mapping>
                  <listener><listener-class>com.acme.shop.Startup</listener-class></listener>
                  <session-config><session-timeout>45</session-timeout></session-config>
                  <error-page><error-code>404</error-code><location>/missing.jsp</location></error-page>
                </web-app>
                """);
        List<String> notes = new ArrayList<>();
        SpringBootReplatformer.convert(module, "3.5.7", notes);
        assertThat(module.resolve("src/main/webapp/WEB-INF/web.xml")).doesNotExist();
        assertThat(read("src/main/java/com/acme/shop/WebConfiguration.java")).contains("@Configuration",
                "public ServletRegistrationBean<com.acme.shop.Report> monthlyReportServlet()",
                "new ServletRegistrationBean<>(new com.acme.shop.Report(), \"/report\", \"/report/*\")", "bean.setName(\"monthly-report\");",
                "bean.setLoadOnStartup(1);", "bean.addInitParameter(\"format\", \"pdf\");",
                "public FilterRegistrationBean<com.acme.shop.AuditFilter> auditFilter()", "bean.addUrlPatterns(\"/*\");", "bean.setOrder(1);",
                "new ServletListenerRegistrationBean<>(new com.acme.shop.Startup())");
        assertThat(read("src/main/resources/application.properties")).contains("server.servlet.context-parameters.region=gh",
                "server.servlet.session.timeout=45m");
        assertThat(notes).anyMatch(n -> n.contains("<error-page>"));
        assertThat(read("pom.xml")).contains("tomcat-embed-jasper");
        assertThat(read("src/main/java/com/acme/shop/Application.java")).contains("extends SpringBootServletInitializer",
                "@ServletComponentScan");
        assertThat(read("pom.xml")).contains("<packaging>war</packaging>", "spring-boot-starter-web", "spring-boot-starter-tomcat",
                "spring-boot-starter-test");
        assertThat(read("src/main/resources/application.properties")).contains("server.servlet.context-path=/shop");
    }

    @Test
    void aGradleBuildGetsSpringBootInPlaceOfThePlatformApi() throws Exception {
        file("settings.gradle", "rootProject.name = 'pay'\n");
        file("build.gradle", """
                plugins {
                    id 'war'
                }

                repositories {
                    mavenCentral()
                }

                dependencies {
                    providedCompile 'jakarta.platform:jakarta.jakartaee-api:10.0.0'
                    implementation 'org.apache.commons:commons-lang3:3.14.0'
                    implementation 'org.hibernate.orm:hibernate-core:6.2.0.Final'
                    testImplementation 'junit:junit:4.13.2'
                }
                """);
        file("src/main/java/com/acme/pay/PayService.java", """
                package com.acme.pay;

                import jakarta.ejb.Stateless;
                import jakarta.persistence.EntityManager;
                import jakarta.persistence.PersistenceContext;

                @Stateless
                public class PayService {
                    @PersistenceContext
                    EntityManager em;
                }
                """);
        List<String> notes = new ArrayList<>();

        assertThat(SpringBootReplatformer.convert(module, "3.5.7", notes)).isTrue();

        String build = read("build.gradle");
        assertThat(build).contains("id 'java'", "id 'org.springframework.boot' version '3.5.7'",
                        "id 'io.spring.dependency-management' version '" + SpringBootReplatformer.DEPENDENCY_MANAGEMENT + "'",
                        "implementation 'org.springframework.boot:spring-boot-starter-web'",
                        "implementation 'org.springframework.boot:spring-boot-starter-data-jpa'",
                        "implementation 'org.apache.commons:commons-lang3:3.14.0'",
                        // Spring Boot chooses Hibernate's version now.
                        "implementation 'org.hibernate.orm:hibernate-core'\n",
                        "testRuntimeOnly 'org.junit.vintage:junit-vintage-engine'",
                        "testImplementation 'org.springframework.boot:spring-boot-starter-test'", "testRuntimeOnly 'com.h2database:h2'",
                        "useJUnitPlatform()")
                // No pages: nothing to deploy to a server, so no WAR and no platform API.
                .doesNotContain("id 'war'", "jakartaee-api", "providedCompile");
        // The server published the WAR under the project's name.
        assertThat(read("src/main/resources/application.properties")).contains("server.servlet.context-path=/pay");
        assertThat(read("src/main/java/com/acme/pay/PayService.java")).contains("@Service");
        assertThat(module.resolve("src/main/java/com/acme/pay/Application.java")).exists();
        assertThat(String.join("\n", notes)).contains("the Gradle build: Spring Boot 3.5.7", "in place of 1 server API dependency",
                "an executable jar (bootJar)");
    }

    @Test
    void aKotlinBuildScriptAndABuildWithoutAPluginsBlockAreWrittenTheirOwnWay() {
        SpringBootReplatformer.Uses rest = new SpringBootReplatformer.Uses(true, false, false, false, true, false, false, false, false);
        String kotlin = SpringBootReplatformer.gradle("""
                plugins {
                    war
                }

                dependencies {
                    compileOnly("javax:javaee-api:8.0")
                }
                """, "3.5.7", rest, true, true, true, new ArrayList<>());
        assertThat(kotlin).contains("    war\n", "id(\"org.springframework.boot\") version \"3.5.7\"",
                        "implementation(\"org.springframework.boot:spring-boot-starter-jersey\")",
                        // Pages: still a WAR, with the embedded server and its JSP engine not packaged twice.
                        "providedRuntime(\"org.springframework.boot:spring-boot-starter-tomcat\")",
                        "providedRuntime(\"org.apache.tomcat.embed:tomcat-embed-jasper\")", "tasks.withType<Test> {", "mavenCentral()")
                .doesNotContain("javaee-api");

        String legacy = SpringBootReplatformer.gradle("""
                buildscript {
                    repositories { mavenCentral() }
                }
                apply plugin: 'war'

                dependencies {
                    compile 'commons-io:commons-io:2.11.0'
                }
                """, "3.5.7", rest, false, false, false, new ArrayList<>());
        // The plugins block comes right after buildscript, where Gradle accepts it.
        assertThat(legacy).containsSubsequence("buildscript {", "}\n", "plugins {", "id 'org.springframework.boot'", "id 'java'", "}\n\ndependencies {")
                .doesNotContain("apply plugin: 'war'");
    }
}
