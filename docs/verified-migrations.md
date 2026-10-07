# Verified migrations

What Renova has been shown to do, end to end, with **no AI**: recipes, text rules and guards only. Each row is a
project migrated to a target, built on the target JDK, with its own tests run. Anyone can repeat it:

```sh
benchmark/fetch-public.sh
cli/bin/renova benchmark --suite benchmark/targets.yaml --out /tmp/targets --configs deterministic
```

Last run: 6 October 2026, on Java 21 and Maven 3.8, Renova at the commit that added this file.

| Project | Build | From | To | Build and tests | Checks |
|---|---|---|---|---|---|
| Spring PetClinic (public, 34 Java files, 41 tests) | Maven | Spring Boot 2.7.3, Java 8 level | Spring Boot 3.5, Java 21, Jakarta EE 10 | Pass | 3/3 |
| Spring PetClinic (same) | Maven | Spring Boot 2.7.3 | Spring Boot 4.1, Java 21, Spring Framework 7 | Pass (run on its own) | – |
| Spring PetClinic (same), built with Gradle 7.5 | Gradle | Spring Boot 2.7.3 | Spring Boot 3.5, Java 21, Gradle 8.5 | Pass | 5/5 |
| Orders service (web, JPA, validation, security) | Maven | Spring Boot 2.7.18, Java 8 | Spring Boot 3.5, Java 21 | Pass | 4/4 |
| Orders service (same) | Maven | Spring Boot 2.7.18 | Spring Boot 4.1, Java 21 | Pass | 3/3 |
| Notes web application (WAR, Spring MVC, Jackson) | Maven | Spring 5.3, javax.servlet 4, Java 11 | Spring Framework 7, Servlet 6.1 (Jakarta EE 11), Java 21 | Pass | 4/4 |
| Billing library | Maven | Java 17 | Java 21 | Pass | 2/2 |
| Billing library | Gradle | Java 17 | Java 21 | Pass | 2/2 |
| Billing library | Maven | Java 17 | Java 25 | Pass (run on its own, on JDK 25) | – |
| Warehouse (two Gradle modules) | Gradle | Java 17 | Java 21 | Pass | 1/1 |
| Legacy libraries (JUnit 4, Mockito 1, Log4j 1, Commons Lang 2, Commons Collections 3, HttpClient 4) | Maven | Java 6 source level | Java 21 with six add-ons: JUnit 5, Mockito 5, Log4j 2, Commons Lang 3, Commons Collections 4, HttpClient 5 | Pass | 5/5 |
| Stock (Hibernate, JPA) | Maven | Hibernate 5.6, javax.persistence | Hibernate 6.6, Jakarta EE 10, Java 21 | Pass | 2/2 |
| Stock (same) | Maven | Hibernate 5.6 | Hibernate 7.1, Jakarta EE 11, Java 21 | Pass | 2/2 |
| Greeter service (validation) | Maven | Micronaut 3.10, Java 11 | Micronaut 4.10, Java 21 | Pass | 3/3 |
| Catalog service (REST, CDI, validation) | Maven | Quarkus 2.16, Java 11 | Quarkus 3.33, Java 21 | Pass | 4/4 |
| Payroll web application (servlet, JSP, six jars in `lib` folders, one of them unpublished) | Ant | Java 6, Servlet 2.5 | A Maven build in the standard layout, Java 21, Jakarta EE 10 | Pass | 5/5 |
| Payroll (same) | Ant | Java 6, Servlet 2.5 | Spring Boot 3.5 WAR that also runs on its own, Java 21 | Pass | 4/4 |
| Ledger (EJB, CDI, JAX-RS, JPA, JBoss descriptors) | Maven | Java EE 7 on JBoss, Java 8 | Spring Boot 3.5 executable jar, Java 21 | Pass | 8/8 |
| Helpdesk (Struts actions, with a test that sets one up by hand) | Maven | Struts 2.5, Java 8 | Struts 7.4, Jakarta EE 10, Java 21 | Pass | 3/3 |
| Tasks service in Kotlin (web, JPA, validation) | Maven | Spring Boot 2.7, Kotlin 1.6, Java 11 | Spring Boot 3.5, Kotlin 1.9, Java 21 | Pass | 4/4 |

The suite's runs take about twenty minutes in total, with dependencies already downloaded.

PetClinic is the public sample at github.com/spring-projects/spring-petclinic (Apache-2.0), taken at the commit
before its maintainers migrated it to Spring Boot 3 by hand. The other projects are synthetic and were written
to exercise one path each; they are in `renova-test-apps`.

The Gradle PetClinic run is the hardest of these: its wrapper is too old to start on Java 21, so Renova ran the
recipes on an installed Java 17, the recipe moved the wrapper to a Gradle that runs on 21, a guard declared the
JAXB API the renamed imports need, and the result was built and tested on Java 21.

## What the checks are

"The build passes" is not enough: a migration can pass by doing less than it should. Each run also checks the
migrated code for what the target requires, for example that the Spring Boot parent is on the target release
and not part-way, that no `javax.persistence` import is left, that a security configuration kept its access
rules, and that a Gradle build was not given a migration plugin. They are listed in
[`benchmark/targets.yaml`](../benchmark/targets.yaml).

The two re-platformed applications were also started and called over HTTP, outside the suite: the Ledger's
JAX-RS resource created and listed accounts at its old address, rejected a request that failed validation and
rolled back a transfer without funds; the Payroll WAR, started with `java -jar`, answered from its servlet and
its JSP page under the context path the server used to give it.

## A larger project that does not finish by itself

Spring PetClinic Microservices at its Spring Boot 2.6 release (public, Apache-2.0): seven Maven modules on
Spring Cloud 2021, built by a Maven wrapper from 2018. Target: Spring Boot 3.5, Java 21, no AI.

- The recipes moved every module to Spring Boot 3.5.16 and Spring Cloud 2025.0, and the sources to jakarta.
- Five guards then made eight build-file edits that the first attempts at this project showed were needed:
  a version property pinned for Spring Boot 2 (`assertj.version`), `javax.validation` dependencies Spring Boot 3
  no longer manages, the JAXB API one module imports, a JUnit provider from 2018 that the upgrade recipe adds
  to Surefire, and the Maven wrapper itself (3.5.4, too old for Spring Boot 3's plugins).
- **Six of the seven modules build, and the five that have tests pass them.** The seventh, the API gateway
  (Spring Cloud Gateway with Resilience4j), does not: one test is left half-converted by the OkHttp
  MockWebServer recipe, and behind it the circuit-breaker configuration needs changes for Spring Cloud 2025.
  Renova reports the migration as failed and names the file. That module is work for the AI repair loop or a
  person; it has not been run with AI here.

It is not in the suite, because the suite holds what passes without AI. `benchmark/fetch-public.sh` fetches it.

## What is not covered here

- **Frameworks that ended before Jakarta EE.** Struts 1, EJB 2.x, JAX-RPC, Jersey 1, Faces managed beans,
  RichFaces and the others in the legacy-frameworks pack are found and planned, and the change goes to AI or a
  person with the mapping written out. None of those AI paths has been run here: they cost tokens on a key.
- **Re-platforming is verified by a start-up test and the project's own tests,** not by comparing behaviour.
  The data source, security domain and anything the server's console configured are listed for a person.
- **Gradle builds of Kotlin, Micronaut and Quarkus applications** have not been run; the Maven ones have.
  Re-platforming to Spring Boot and the generated build for Ant projects are Maven only.
- **Ant builds with several modules** (one build.xml calling others) get one Maven module from the root build.
- **Identifying jars needs the network.** Offline, an Ant project's libraries stay files and are not upgraded.
- **Changes that need judgement.** The original suite ([`benchmark/suite.yaml`](../benchmark/suite.yaml)) has
  four applications built so that the last changes need AI or a person: removed JDK APIs whose replacement
  depends on other code, upload limits that must not change, runtime-only failures. Without AI those builds fail,
  by design, and the report names the steps that were left; with a provider configured they go to the AI
  repair loop. Running that suite with AI costs tokens on your own key.
- **Behaviour.** These runs compare builds and tests, not the running application. `--verify-behaviour` runs
  the original and the migrated application side by side in Docker; it supports servlet-container WARs today.
- **Large codebases.** The biggest project that passes by itself is PetClinic; the seven-module project above
  gets most of the way. Applications of hundreds of modules have not been tried.
