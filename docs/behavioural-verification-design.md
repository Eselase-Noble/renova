# Behavioural verification design

**Status:** proposal, not yet implemented
**Scope:** `engine/core` (contracts, comparison, report), ecosystem plugins (launching apps, finding endpoints), CLI and benchmark

## 1. Problem

Today a migration is verified by building the migrated project and running its own tests. That catches
compile errors and whatever the tests cover. Legacy apps usually have few tests, and the changes that
hurt customers after a platform upgrade are often behavioural, with no compile error:

| Change | Effect |
|---|---|
| Spring 6 URL matching | `/orders.do` and `/orders/` stop answering, with no error at startup |
| Multipart limits moved to the container | Uploads that used to work are rejected |
| JSP / EL and taglib changes | Pages render differently or blank (SiteMesh on Tomcat 10.1) |
| JSON and XML binding (Jackson, JAXB) | Field names, date formats or null handling change in API responses |
| Character encoding defaults (Java 18 made UTF-8 the default) | Text read or written without an explicit charset changes |
| JPA provider upgrade | Different SQL, flush order or lazy-loading behaviour; different rows written |

The only reliable test is to run the original and the migrated application side by side, send both
the same requests, and compare what they answer and what they change.

## 2. Requirements

1. **Same inputs, isolated runs.** Both apps run from scratch with the same data, in sandboxes that
   cannot reach production systems. Outbound network is blocked by default.
2. **Each app on its own platform.** The original runs on its legacy runtime (for example Java 8 and
   Tomcat 9), the migrated copy on the target (Java 21 and Tomcat 10.1).
3. **Differences that matter.** Comparison ignores what legitimately varies between runs (timestamps,
   generated ids, session cookies, header order, the server banner) and reports the rest precisely.
4. **Actionable.** Each difference names the request, what differed, and where possible the code
   that handles it, so it can go to a person or to the AI repair loop as a structured error.
5. **Useful without customer effort, better with it.** Endpoints found in the code give a baseline.
   Scenario files or recorded traffic from the customer give real coverage.

## 3. Approach: differential testing

```
                  ┌──────────── scenarios: discovered endpoints, scenario files, recorded traffic ───────────┐
                  ▼                                                                                          ▼
    ┌───────────────────────────────┐                                              ┌───────────────────────────────┐
    │ baseline sandbox              │                                              │ candidate sandbox             │
    │ original build, legacy runtime│                                              │ migrated build, target runtime│
    │ (Java 8, Tomcat 9)            │                                              │ (Java 21, Tomcat 10.1)        │
    │ + fresh database from seed    │                                              │ + fresh database from seed    │
    └──────────────┬────────────────┘                                              └──────────────┬────────────────┘
                   └────────────► responses + database changes ◄──────────────────────────────────┘
                                              ▼
                       normalise (timestamps, ids, sessions) → compare → behaviour report
                                              ▼
                     differences → manual follow-up, or BuildError-like input to AI repair
```

### 3.1 Sandboxes

- **Docker** is the default: one container per app with the matching JDK and server image (for
  example `tomcat:9-jdk8` and `tomcat:10.1-jdk21`), on an internal network with no outbound access.
  Images are pinned by digest for reproducible runs.
- **Local fallback** when Docker is unavailable: the plugin launches the server itself (embedded
  Tomcat, or the app's own launcher) on a free port with the matching JDK, if one is installed.
- The ecosystem plugin decides how to build, package and launch an app (`BehaviourRunner` SPI,
  section 4). Java: WAR to a servlet container or JBoss/WildFly image; Spring Boot jar with `java -jar`.

### 3.2 Scenarios

Three sources, combined:

1. **Discovered endpoints** (no customer input). The plugin lists entry points: Spring
   `@RequestMapping` and its variants, `web.xml` servlets and their URL patterns, JAX-RS `@Path`, and
   JSPs. Each becomes a GET request, plus the legacy URL forms at risk (`.do` suffix, trailing slash).
   This catches missing routes, startup failures and rendering errors.
2. **Scenario files** (`renova-scenarios.yaml`), written by the customer or generated with AI and then
   reviewed: ordered requests with methods, parameters, bodies, uploads, logins and values carried
   from one response to the next request.
3. **Recorded traffic** (later phase): HAR files or access logs from a test environment, replayed
   against both apps.

### 3.3 Comparison

- **Status code** and **redirect target**, exactly.
- **Headers**: an allow-list (`Content-Type`, `Content-Disposition`, `Cache-Control`, `Location`, CORS).
- **Body** by content type: JSON compared structurally (ignored paths configurable), XML canonically,
  HTML after normalising whitespace with configurable ignored regions, binary by hash.
- **Normalisers** for timestamps, UUIDs, sequence ids and CSRF tokens, applied to both sides before
  comparing. Suggested by observing the baseline twice: whatever differs between two baseline runs
  with the same input is noise, not a migration difference.
- **Database effects** (phase 2): both apps start from the same seed (Testcontainers database or
  embedded H2). After each scenario, changed rows are compared table by table.

### 3.4 Results

- `.renova/behaviour.md` and `.renova/behaviour.json`: scenarios run, matches, differences with
  both responses (truncated), and the handler where the plugin can find it (controller method for a
  Spring route).
- The migration report gains a behaviour section, and the benchmark a "behaviour checks" metric.
- Differences can be sent to the AI repair loop like build errors, attributed to the handler's file,
  with both responses as evidence. Same safety rules: the model only edits files offered as editable,
  and the comparison runs again after each round.

## 4. Contracts

```java
/** Builds, starts and stops one version of an app in a sandbox. Supplied by the ecosystem plugin. */
public interface BehaviourRunner {
    RunningApp start(Path project, Platform platform, Sandbox sandbox) throws Exception;   // baseline or candidate
    List<Scenario> discover(ProjectModel model);                                          // entry points
}

public record Platform(String jdk, String server) { }             // e.g. ("8", "tomcat:9"), ("21", "tomcat:10.1")

public interface RunningApp extends AutoCloseable {
    URI baseUrl();
    String logs();                                                 // for startup failures
}

public record Scenario(String id, List<Step> steps) { }
public record Difference(String scenario, String step, Kind kind, String expected, String actual, String handlerFile) { }
```

`EcosystemPlugin` gains `default Optional<BehaviourRunner> behaviourRunner()`. The engine owns
scenario execution, normalisation, comparison and reporting, so every ecosystem gets the same
behaviour report.

## 5. Delivery plan

| Phase | Delivers |
|---|---|
| 1 | Docker sandboxes for WAR apps (Tomcat), endpoint discovery for Spring MVC and `web.xml`, GET scenarios, status / header / body comparison with normalisers, behaviour report, `--verify-behaviour` |
| 2 | Scenario files (POST, uploads, logins, carried values) and database effects on a seeded database |
| 3 | Differences fed to AI repair; behaviour metrics in the benchmark |
| 4 | JBoss/WildFly and Spring Boot runners; recorded-traffic replay (HAR, access logs) |

Phase 1 needs no customer input and catches the most expensive surprises: an app that no longer
starts, routes that disappeared, and pages that render differently.

## 6. Open questions

1. Should Docker be required for behavioural verification, or is the local fallback worth its cost?
2. Where should baseline JDKs and server images come from in air-gapped customer networks: a
   customer registry mirror, or images Renova ships?
3. Should scenario files generated with AI be marked as unreviewed until a person approves them?
