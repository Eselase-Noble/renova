# Verified migrations

What Renova has been shown to do, end to end. First with **no AI**: recipes, text rules and guards only; then
the [paths that need AI](#with-ai). Each row is a
project migrated to a target, built on the target JDK, with its own tests run. Anyone can repeat it:

```sh
benchmark/fetch-public.sh
cli/bin/renova benchmark --suite benchmark/targets.yaml --out /tmp/targets --configs deterministic
```

Last run: 6 October 2026, on Java 21 and Maven 3.8; the three Gradle rows at the end and the AI runs on
7 October 2026.

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
| Tasks service in Kotlin (same) | Gradle 7.5 | Spring Boot 2.7, Kotlin 1.6, Java 11 | Spring Boot 3.5, Kotlin 1.9, Java 21, Gradle 8.5 | Pass | 4/4 |
| Greeter service (same) | Gradle 7.5 | Micronaut 3.10, Java 11 | Micronaut 4.10, Java 21, Gradle 8.5 | Pass | 4/4 |
| Catalog service (same) | Gradle 7.5 | Quarkus 2.16, Java 11 | Quarkus 3.33, Java 21, Gradle 9.1 | Pass | 4/4 |

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

## .NET

Run on 7 October 2026 with the .NET SDK 10.0.401 on Linux, no AI:
`cli/bin/renova benchmark --suite benchmark/dotnet.yaml --out /tmp/dotnet --configs deterministic`.

| Project | From | To | Build and tests | Checks |
|---|---|---|---|---|
| Billing (C# library and NUnit tests; project files in the format before the SDK, `packages.config`, an embedded resource, a stale source file on disk) | .NET Framework 4.8 | .NET 10 | Pass (7 tests) | 6/6 |
| Ledger (Visual Basic library and tests on the MSTest that came with Visual Studio; no solution file) | .NET Framework 4.7.2 | .NET 10 | Pass (4 tests) | 5/5 |
| Orders (ASP.NET Core web API, Entity Framework Core, xUnit tests that start the application in memory) | .NET Core 3.1 | .NET 10 | Pass (4 tests) | 3/3 |
| Orders (same) | .NET Core 3.1 | .NET 8 | Pass (4 tests) | 2/2 |
| Stateless (public, Apache-2.0: a portable class library, three console examples, tests on NUnit 2.4 kept as a file) | .NET Framework 4.0 / PCL profile 136, as of February 2016 | .NET 10, NUnit 3.14 | Pass (73 tests) | 6/6 |

Stateless is the state-machine library at github.com/dotnet-state-machine/stateless, taken at a commit from
before its maintainers moved it to the SDK style; `benchmark/fetch-public.sh` fetches it. Five of its tests
used `[ExpectedException]`, which Renova rewrote as `Assert.Throws`. The other three projects are synthetic
and small. The .NET Framework originals could not be built on this machine (their format needs Visual Studio's
MSBuild on Windows), so what is shown is that the migrated projects build and their tests pass, not that the
originals did. Not run: Entity Framework 6, multi-targeted libraries, Web Forms.

**Compiled, not yet run.** Three Windows desktop applications (`benchmark/dotnet-windows.yaml`) migrate to
`net10.0-windows` and compile on Linux with the Windows targeting pack, 12 of 12 checks: Desk (C# Windows
Forms, .NET Framework 4.7.2), Notes (C# WPF, 4.8) and Till (Visual Basic Windows Forms with the application
framework, 4.8). Their tests and the applications themselves have not been run: that, the original .NET
Framework builds, and the MVC application beside its original under IIS Express are the subject of
[windows-test-plan.md](windows-test-plan.md).

## PHP

Run on 7 October 2026, no AI, verified on PHP 8.4.23 with Composer 2.9:
`cli/bin/renova benchmark --suite benchmark/php.yaml --out /tmp/php --configs deterministic`.

| Project | From | To | Install, syntax and tests | Checks |
|---|---|---|---|---|
| Ledger (a library using what PHP 8 removed: `each()`, curly-brace offsets, reversed `implode()`, `(real)`; PHPUnit 7 tests with `@expectedException`) | PHP 7.4, PHPUnit 7.5 | PHP 8.4, PHPUnit 9.6 | Pass (7 tests) | 6/6 |
| FastRoute 1.3.0 (public, BSD-3-Clause: the request router under Slim and Lumen) | PHP 5.4 and later, PHPUnit 4.8 | PHP 8.4, PHPUnit 9.6 | Pass (204 tests) | 4/4 |
| Orders (Laravel's own application skeleton with an orders API, a model, a factory, feature tests on SQLite) | Laravel 10, PHP 8.1 | Laravel 13, PHP 8.4 | Pass (7 tests) | 5/5 |
| Shop (the same on the Laravel 8 skeleton, with `fruitcake/laravel-cors`, `facade/ignition` and `$dates`) | Laravel 8, PHP 7.3/8.0 | Laravel 13, PHP 8.4 | Pass (7 tests) | 5/5 |
| Orders (same as above) | Laravel 10 | Laravel 12, PHP 8.4 | Pass (7 tests) | 2/2 |

Each original was first run as it was, on the PHP it was written for, in Docker (PHP 7.4, 7.1, 8.2 and 8.0):
all tests passed there, one of FastRoute's 204 skipped. The two Laravel applications are Laravel's real
skeletons with one small feature added; no application anyone runs a business on has been tried. Not run:
`php-to-8.3` and `php-to-8.5`, `laravel-11`, any PHP path with AI, Symfony, a project without tests.

## With AI

Run on 7 October 2026 with Claude Opus (`claude-opus-5-5`), retrieval on, on the maintainer's own key. AI output
varies between runs; these are single runs, not averages. Repeat them with
`cli/bin/renova benchmark --suite benchmark/legacy.yaml --out /tmp/legacy --ai anthropic`.

| Project | From | To | Build and tests | Behaviour against the original | Tokens in / out |
|---|---|---|---|---|---|
| Couriers (Jersey 1 resource and client, Commons HttpClient 3 client, 9 tests, two of the classes tested against a local HTTP server) | Jersey 1.19, HttpClient 3.1, Java 8 | Jersey 3.1, the JAX-RS client API, HttpClient 5, Jakarta EE 10, Java 21 | Pass | Same: 6 of 6 requests (Tomcat 9 beside Tomcat 10.1) | 18,536 / 10,551 |
| Tickets (Struts 1 actions, form bean with validation, `struts-config.xml`, three pages with Struts tags) | Struts 1.3, Java 8 | Spring MVC 6.2 controllers on the same `*.do` addresses, Jakarta EE 10, Java 21 | Pass | 6 of 9 requests the same; 3 differ in the form's markup only (`name=` became `id=`, error text inside a `<span>`), with the same statuses and messages | 6,885 / 11,221 |
| Spring PetClinic Microservices (seven modules, see below) | Spring Boot 2.6, Spring Cloud 2021 | Spring Boot 3.5, Spring Cloud 2025, Java 21 | Pass, in four repair rounds | Not run: several applications | 29,714 / 22,184 |
| Shop (.NET: ASP.NET MVC 5 with a Web API 2 controller, three Razor views and a layout, a style bundle, `Global.asax`, `Web.config`; beside a class library with NUnit tests) | ASP.NET MVC 5.2 on .NET Framework 4.8 | ASP.NET Core on .NET 10 | Pass, in one repair round (3 tests, of the library) | Not compared: the original needs IIS on Windows. The migrated application was started and called: 11 addresses answer as the code says (pages, the `category/{category}` route, 404 and 400, JSON from the API, the stylesheet), the form saves, rejects invalid input with the same messages, and refuses a post without its anti-forgery token | 13,656 / 11,764 |

What these runs changed in Renova:

- **A framework is replaced in one request.** Asked for one Struts action at a time, the model declined, rightly:
  an action cannot become a controller without its form, its pages and `web.xml`. A rule can now say that what it
  finds changes together (`params.together`), name the other files of the change (`params.with`) and where new
  files may be added (`params.create`). The Struts 1 rule does; the request above added a configuration class,
  a validator and a controller for the form page.
- **A test a recipe left uncompilable can be repaired.** Tests are still never changed to make them pass. But
  the PetClinic gateway's test was half-converted by a recipe and no longer compiled, so it described nothing;
  the compiler's errors in a test that an earlier stage rewrote may now be fixed, and nothing else in it.
- **A failed test reports its last cause.** "Failed to load ApplicationContext", cut at 400 characters, sent
  repairs guessing; the report now carries the end of the chain ("No qualifying bean of type BulkheadRegistry").
  A build error without words is no longer passed on empty.
- **A whole-layer request can remove files.** ASP.NET Core has no `Global.asax`, `Web.config` or `App_Start`;
  left in place they stop the build. A request that replaces a layer may now list the files it was given that
  the result no longer has. Nothing else can be removed, and never a test.
- **`web.xml` follows Jersey.** A text rule renames the Jersey 1 servlet there; the build passed without it and
  the application would not have deployed.
- **Struts 1 projects are no longer offered the Struts 2 → 7 add-on.**

The default of three repair rounds stops the seven-module project one round short; it passed with
`--max-ai-iterations 6`.

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
  Renova reports the migration as failed and names the file. With AI the module is repaired and all seven
  build: see [With AI](#with-ai).

It is not in the suite, because the suite holds what passes without AI. `benchmark/fetch-public.sh` fetches it.

## What is not covered here

- **Most frameworks that ended before Jakarta EE.** Jersey 1, Commons HttpClient 3 and Struts 1 have been run
  with AI (above), each on one small application. EJB 2.x, JAX-RPC, Faces managed beans, RichFaces, iBATIS,
  Hibernate's legacy Criteria and the others in the legacy-frameworks pack are found and planned, with the
  mapping written out, and have not been run. A Struts 1 application too large for one request is sent in
  halves, each with the pages and descriptors; that has not been tried on a real one.
- **Re-platforming is verified by a start-up test and the project's own tests,** not by comparing behaviour.
  The data source, security domain and anything the server's console configured are listed for a person.
- **Gradle.** Re-platforming to Spring Boot and the generated build for Ant projects are Maven only, and so is
  behavioural verification. Kotlin build scripts (`build.gradle.kts`) are handled by the same guards and are
  covered by unit tests, not by a migrated project.
- **Ant builds with several modules** (one build.xml calling others) get one Maven module from the root build.
- **Identifying jars needs the network.** Offline, an Ant project's libraries stay files and are not upgraded.
- **Changes that need judgement.** The original suite ([`benchmark/suite.yaml`](../benchmark/suite.yaml)) has
  four applications built so that the last changes need AI or a person: removed JDK APIs whose replacement
  depends on other code, upload limits that must not change, runtime-only failures. Without AI those builds fail,
  by design, and the report names the steps that were left; with a provider configured they go to the AI
  repair loop. Running that suite with AI costs tokens on your own key.
- **Behaviour.** The runs without AI compare builds and tests, not the running application.
  `--verify-behaviour` runs the original and the migrated application side by side in Docker: servlet-container
  WARs and, since 7 October 2026, Spring Boot applications. Run on the Orders service (Spring Boot 2.7 on
  Java 8 beside 3.5 on Java 21), it reported the one change Spring Boot 3 is known for: `/orders/` with a
  trailing slash no longer matches.
- **Large codebases.** The biggest project that passes by itself is PetClinic; the seven-module project above
  gets most of the way. Applications of hundreds of modules have not been tried.
