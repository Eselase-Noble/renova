# Renova

**Assess and modernise legacy software systems, safely and repeatably.**

Renova analyses a legacy codebase, produces a migration plan, and carries out the migration on a copy
of the project. Deterministic rewrites handle the mechanical bulk. Context-dependent changes go to an
AI model or a person, and the real build checks every change.

> **Status:** early development (`0.1.0-SNAPSHOT`). The engine, command-line interface, web console and desktop
> app, IntelliJ plugin and VS Code extension work for Java, .NET (C# and Visual Basic) and PHP (Composer,
> Laravel) projects. What
> has been shown to work, and what has not, is in [docs/verified-migrations.md](docs/verified-migrations.md);
> a one-page summary is in [docs/product-brief.md](docs/product-brief.md). See the [roadmap](#roadmap).

---

## Contents

- [Why Renova](#why-renova)
- [How it works](#how-it-works)
- [Repository layout](#repository-layout)
- [Getting started](#getting-started)
- [Playbooks](#playbooks)
- [Extending Renova](#extending-renova)
- [Deploying](#deploying)
- [Licensing](#licensing)
- [Roadmap](#roadmap)
- [Contributing](#contributing)
- [License](#license)

## Why Renova

Platform upgrades such as Java 8 → 21, `javax` → `jakarta`, or a new application server touch thousands
of files. Most of those edits are mechanical. A small number need judgement, and those usually take
most of the effort. Renova treats the two differently:

- **Assessment before change.** A read-only analysis reports every affected place, grouped by
  category and severity, with an *automation rate*: the share of the work that needs no human
  decision.
- **Migrations are data.** A migration path is a YAML *playbook* of rules. New targets, or a client's
  own conventions, are added without changing the engine.
- **The right tool for each change:** AST-aware recipes ([OpenRewrite](https://docs.openrewrite.org/))
  for code, text rules for JSP, TLD and configuration files, an AI provider for context-dependent
  edits, and clear guidance for anything left to a person.
- **Safe by construction.** The source project is never modified. Each stage of a migration is a
  separate git commit in an isolated workspace, so every change can be reviewed, audited or reverted.
- **Verified.** The migrated project is built and its tests are run (`--skip-tests` to only compile).
  Compiler, build-file and test failures become structured errors that feed an automatic repair loop. One repair can change a source file and its build file together. Tests are never changed to make them pass; the one exception is a test that a rewrite step left uncompilable, where only the compiler's errors may be repaired.
- **Bring your own AI key.** Each user or organisation supplies its own provider credentials. Token
  usage is reported per migration.
- **Any ecosystem.** The engine has no Java-specific code. Java is the first plugin, .NET the second, PHP the third.

## How it works

```
             ┌────────────┐   ┌──────────┐   ┌──────────────────────────── migrate (on a copy) ────────────────────────────┐
 project ──► │  analyze   │ ─►│   plan   │ ─►│ recipe ─► replace ─► ai ─► guards ─► verify (build) ─► ai repair ─► report │
             │ (read-only)│   │ ordered  │   │              every stage is one git commit in the workspace                │
             └────────────┘   └──────────┘   └─────────────────────────────────────────────────────────────────────────────┘
```

**Guards** are playbook rules with `phase: guard`. They run on the migrated code before the build is
verified, and again on each AI repair round's edits before the rebuild, and catch what earlier stages introduced or left behind, for example a container API moved to
`compile` scope, a build plugin too old for the new JDK, a compiler target that no longer matches the
source level, a package the code imports that no declared dependency supplies, a dependency a recipe left
without a version or declared twice, or a Spring `Assert` call a recipe could not convert.

Every finding belongs to a change category, and plan steps run in the order A → E → B → C → D:

| Code | Category | Examples |
|---|---|---|
| A | Build, platform and descriptors | Java level, `web.xml` schema, build variants |
| B | Namespace renames | `javax.servlet` → `jakarta.servlet` in Java, JSP and TLD files |
| C | Removed or changed APIs | `sun.misc.BASE64Decoder`, Spring 6 API removals |
| D | Runtime and container behaviour | URL matching defaults, servlet-container settings |
| E | Dependency declarations | Libraries previously supplied only transitively |

## Repository layout

| Path | Description | Status |
|---|---|---|
| [`engine/core`](engine) | Ecosystem-neutral engine: playbooks, plugin SPI, analysis, planning, migration, AI loop, reports | Working |
| [`engine/java`](engine) | Java plugin: Maven/Gradle model, Java detectors, OpenRewrite fixer, Maven verifier, bundled playbooks | Working |
| [`engine/dotnet`](engine) | .NET plugin: C# and Visual Basic project model, .NET detectors, project-file fixer, `dotnet` verifier, bundled playbooks | First targets working |
| [`engine/php`](engine) | PHP plugin: Composer project model, PHP detectors, Rector and `composer.json` fixers, Composer and PHPUnit verifier, bundled PHP and Laravel playbooks | First targets working |
| [`engine/ai-anthropic`](engine) | AI provider for Claude, using each user's own Anthropic API key | Working |
| [`engine/ai-openai`](engine) | AI provider for OpenAI or any OpenAI-compatible server (Azure OpenAI, vLLM, Ollama), using the user's own key | Working |
| [`cli`](cli) | `renova` command for terminals and CI pipelines | Working |
| [`web`](web) | REST API (Spring Boot) and web console (Next.js): local mode for one machine, or accounts and organisations on your own server | Working |
| [`desktop`](desktop) | JavaFX desktop app running the engine on your machine, packaged with jpackage | Working |
| [`ide`](ide) | IntelliJ IDEA plugin and VS Code extension: findings in the editor, migrate from the IDE | Working |

## Getting started

### Prerequisites

- JDK 21 or later
- Maven 3.8 or later
- Git, used to version migration workspaces
- Network access to Maven Central, or a mirror configured in `settings.xml`
- For .NET projects: the .NET SDK of the target (10 or 8) and network access to nuget.org or your own feed.
  Renova looks for `dotnet` on the PATH, in `DOTNET_ROOT`, `~/.dotnet` and the usual install folders;
  `RENOVA_DOTNET` names another one
- For PHP projects: Composer, and the PHP of the target (8.3, 8.4 or 8.5) beside whatever PHP is on the PATH.
  Renova looks for `php` on the PATH, in the folders of `RENOVA_PHP`, in `~/.local/share/renova/php` and where
  Homebrew, Herd, phpbrew, asdf, XAMPP and Laragon install it

### Build

```sh
git clone https://github.com/Eselase-Noble/renova.git
cd renova
mvn install
```

This builds and tests every module and produces the CLI at `cli/target/renova.jar`.

### Use

```sh
# List installed ecosystems and playbooks
cli/bin/renova playbooks

# Assess a project (read-only). Markdown by default, JSON for tooling.
cli/bin/renova analyze /path/to/project
cli/bin/renova analyze /path/to/project -f json -o assessment.json

# Assess every project under a folder and rank them, easiest to migrate first (Markdown, JSON or CSV)
cli/bin/renova portfolio /path/to/all-projects
cli/bin/renova portfolio /path/to/all-projects -f csv -o portfolio.csv

# Migrate a copy of the project into an empty directory
cli/bin/renova migrate /path/to/project --out /path/to/migrated \
    [--playbook ID|FILE] [--maven-settings settings.xml] [--offline] [--skip ai] [--skip-tests]
```

After a migration, `/path/to/migrated` contains:

- the migrated project, with one git commit per stage (`git log` lists them)
- `.renova/report.md`: a human-readable migration report, structured like a migration guide
- `.renova/report.json`: the same data for CI gates and dashboards

Verification runs the project's own tests, because code that compiles on the new JDK can still fail
at runtime. A failed test is attributed to the project code that threw, so repairs target that code.

`migrate` exits with `0` when the migrated build passes (and, with `--verify-behaviour`, the application
behaves the same), `1` otherwise, and `2` on usage errors.

### Verify behaviour

A passing build and passing tests do not prove the application behaves the same. With
`--verify-behaviour`, Renova runs the original and the migrated application side by side in Docker and
compares their answers:

```sh
cli/bin/renova migrate /path/to/project --out /path/to/migrated --verify-behaviour
cli/bin/renova verify-behaviour /path/to/migrated     # again, on an existing workspace
```

- The original is built from the workspace's baseline commit with the legacy JDK and runs on the legacy
  container (Java 8, Tomcat 9); the migrated build runs on the target (Java 21, Tomcat 10.1). Images are set
  in the playbook (`settings.behaviour`).
- Both run on an internal Docker network with no outside access; the requests come from a probe container on
  the same network.
- Requests come from the code: Spring MVC routes (also with a trailing slash, which Spring 6 stopped
  matching), `web.xml` servlets and JSP pages.
- Status codes, redirects, `Content-Type` and bodies are compared, ignoring values that change on every
  request (session ids, UUIDs, timestamps). Results: `.renova/behaviour.md` and `.renova/behaviour.json`.

For real coverage, add `renova-scenarios.yaml` to the project: multi-step scenarios with forms, JSON or file
uploads, values captured from one answer for the next request, and sessions kept per run. It can also give each
version its own PostgreSQL database from the same seed; Renova then compares the rows each version adds and
removes in every scenario:

```yaml
database:
  init: db/schema.sql                      # seed; each version gets a fresh copy
  ignoreColumns: [filed_at]
  app: { claims.db.url: "jdbc:postgresql://${host}:5432/app", claims.db.user: renova, claims.db.password: renova }
scenarios:
  - id: file-and-read-a-claim
    steps:
      - { method: POST, path: /claims, form: { holder: Ama Mensah, amount: "1520.75" },
          capture: { claim: 'header:Location:/claims/(\d+)' } }
      - { path: "/claims/${claim}" }
  - id: import-stock-file
    steps:
      - { method: POST, path: /items/import, multipart: { file: { filename: stock.csv, content: "sku,qty\nA-1,4\n" } } }
```

With an AI provider configured, differences go to the same repair loop as build errors (`--no-behaviour-repair`
to only report them). Each difference is attributed to the file that handles the request: the controller method,
the JSP, or for URL-matching differences such as trailing slashes, the Spring configuration. Each round rebuilds,
runs the tests and compares again, and the guards check every round's edits. A difference that is intended, such
as dropping trailing-slash URLs on purpose, is listed under `accept:` in the scenario file:

```yaml
accept:
  - 'GET /items/: status 200 became 404'
```

For PHP it runs the application on PHP's own web server in the official PHP image, the original on the PHP
it was written for and the migrated one on the target's; a Laravel application gets an SQLite database of its
own from its migrations. For Java it needs Maven projects with one application: a Spring Boot application, started with
`java -jar` (the original on the Java release it was written for, the migrated one on the target's), or a WAR
on a servlet container. Applications that need a full Jakarta EE server, Gradle builds and projects with
several applications are reported as skipped. See
[docs/behavioural-verification-design.md](docs/behavioural-verification-design.md).

To measure Renova itself across several apps and configurations (with and without AI or retrieval), use
`cli/bin/renova benchmark`; see [`benchmark/README.md`](benchmark/README.md).

### Configure AI (bring your own key)

AI steps are optional. Without a provider they appear in the report as manual work. To enable them,
give Renova **your own** API key in any of these ways:

```sh
# 1. Store it in your user config file (prompted without echo; file is readable only by you)
cli/bin/renova config set-key anthropic          # or: openai
cli/bin/renova config set ai.provider anthropic  # or: openai

# 2. Or use environment variables
export ANTHROPIC_API_KEY=...        RENOVA_AI_PROVIDER=anthropic
export OPENAI_API_KEY=...           RENOVA_AI_PROVIDER=openai

# 3. Or point at a .env file kept outside the repository
cli/bin/renova migrate <project> --out <dir> --ai anthropic --env-file ~/secrets/renova.env

# Verify the key and model without generating anything (free)
cli/bin/renova config check
cli/bin/renova config show           # effective settings and where each came from; keys masked
```

Settings are resolved in this order: command-line flags, environment variables, `--env-file`, then
the user config file (`~/.config/renova/config.properties`). Renova never reads the `.env` of the
project being migrated, never accepts keys as command-line arguments, and never writes keys to logs
or reports.

| Setting | Environment variable | Default |
|---|---|---|
| `ai.provider` | `RENOVA_AI_PROVIDER` | `none` |
| `ai.model` | `RENOVA_AI_MODEL` | provider default (`claude-opus-5-5` / `gpt-5.5`) |
| `ai.effort` | `RENOVA_AI_EFFORT` | Anthropic: `high`. OpenAI: not sent unless set |
| `ai.fallbacks` | `RENOVA_AI_FALLBACKS` | Anthropic only: `default` (server-side refusal fallbacks; `off` to disable) |
| `anthropic.apiKey` | `ANTHROPIC_API_KEY` | — |
| `anthropic.baseUrl` | `ANTHROPIC_BASE_URL` | Anthropic API |
| `openai.apiKey` | `OPENAI_API_KEY` | — |
| `openai.baseUrl` | `OPENAI_BASE_URL` | OpenAI API. Point it at an on-premises OpenAI-compatible server to keep code in your network |
| `rag.enabled` | `RENOVA_RAG` | `true`. `--rag` / `--no-rag` override it for one migration |
| `rag.budget` | `RENOVA_RAG_BUDGET` | `0.3`: the share of each AI request that retrieved context may use |

### Retrieval (RAG)

Each AI request also carries context retrieved for it, with no extra key or model call (`--no-rag` to turn it off):

- **Related project code:** for Java, the project types a file extends or imports, the tests that use it, the configuration files
  that name it, and the classes a Spring XML or JSP file names. These files are sent as reference only and
  can never be edited.
- **Curated knowledge:** short notes shipped with the playbook (`knowledge:`), chosen when a note's trigger
  terms appear in the request's rules, build errors or files, and ranked with BM25.

Each AI exchange's log (`.renova/ai/NNN.md`) lists the context it received. RAG is on by default because the
benchmark showed it decides migrations where a fix depends on other code; see [docs/rag-design.md](docs/rag-design.md).

## Playbooks

A playbook is a list of rules. Each rule says what to detect and how to fix it:

```yaml
id: java8-to-21-jakarta-ee10
ecosystem: java
rules:
  - id: javax-in-jsp
    title: Rename javax.servlet references in JSP pages and tag files
    category: B
    severity: BLOCKER
    detect:
      type: fileContains
      include: ["**/*.jsp", "**/*.tag"]
      pattern: "javax\\.servlet"
    fix:
      strategy: replace
      include: "**/*.{jsp,tag}"
      find: "javax.servlet"
      replace: "jakarta.servlet"
```

| Fix strategy | Who resolves it |
|---|---|
| `recipe` | An ecosystem rewrite tool (OpenRewrite for Java), deterministic and type-aware |
| `replace` | Text replacement driven by the playbook, for files without a parser |
| `gradle` | Edits to Gradle build files in the file's own style: `addDependency`, `removeDependency`, `setPluginVersion`, `setWrapperVersion` |
| `maven` | Format-preserving pom.xml edits: `setScope`, `setPluginVersion`, `setProperty`, `changeProperty`, `setParentVersion`, `addDependency`, `addAnnotationProcessor`, `setVersion`, `removeDuplicates` |
| `dotnet` | Edits to .NET project files: `convertToSdkStyle`, `setTargetFramework`, `setPackageVersion`, `addPackage`, `replacePackage`, `removePackage`, `setProperty` |
| `composer` | Edits to `composer.json`: `setPhp`, `setVersion`, `add`, `remove`, `replace` |
| `rector` | [Rector](https://getrector.com), the rewrite tool for PHP, deterministic and type-aware. Installed into Renova's cache, never into the project |
| `dotnet-source` | Rewrites of C# that follow from the text alone: `nunit3` (NUnit 2 tests to NUnit 3) |
| `ai` | The configured AI provider. The build verifies the result. One request per file; with `params.together`, one request for everything a rule found plus the files named in `params.with`, new files where `params.create` allows, and removal of files the result no longer has: for a change no single file holds, such as replacing a web framework |
| `manual` | A person, guided by the rule's `hint` in the report |

### Targets

Every target below has been run end to end without AI, with a passing build and tests, on at least one
project, including the public Spring PetClinic sample: see [docs/verified-migrations.md](docs/verified-migrations.md).

A playbook is a target: where the project should end up. Renova suggests the one that fits the project, and
any other can be chosen (`--playbook ID`, the **Target** selector on a project in the console, or the playbook
list in the desktop app):

| Playbook | Takes | To | Suggested for |
|---|---|---|---|
| `java-to-17`, `java-to-21`, `java-to-25` | Any Java project | That Java version, and nothing else: frameworks and javax or jakarta APIs stay | Projects without Java EE or Spring Boot (`java-to-21`) |
| `java8-to-21-jakarta-ee10` | Java EE (javax) web applications, Spring 5 or earlier | Java 21, Jakarta EE 10, Spring 6, for Tomcat 10.1/11 and WildFly 27+ / JBoss EAP 8 | WAR projects and projects using javax APIs |
| `java-to-21-jakarta-ee11-spring7` | The same applications, or ones already on Jakarta EE 10 | Java 21, Jakarta EE 11 (Servlet 6.1), Spring Framework 7, for Tomcat 11 and WildFly 37+ | Chosen by hand: the latest of each |
| `spring-boot-3` | Spring Boot 2 applications | Spring Boot 3.5, Java 21, Jakarta EE 10 | Spring Boot 2 applications |
| `spring-boot-4` | Spring Boot 2, 3 or 4.0 applications | Spring Boot 4.1, Java 21, Spring Framework 7 | Spring Boot 3 applications |
| `micronaut-4` | Micronaut 2 or 3 applications | Micronaut 4, Java 21 | Micronaut applications |
| `quarkus-3` | Quarkus 1 or 2 applications | Quarkus 3.33, Java 21, Jakarta EE 10 | Quarkus applications |
| `jakarta-ee-to-spring-boot` | Java EE or Jakarta EE applications on an application server (EJB, CDI, JAX-RS, JPA, servlets) | Spring Boot 3.5 on Java 21, without a server: a change of platform | Chosen by hand |

**From an application server to Spring Boot.** `jakarta-ee-to-spring-boot` keeps the standard APIs Spring Boot
runs as they are (REST resources stay JAX-RS and run on Jersey, entities stay JPA, Bean Validation and `@Inject`
stay) and replaces what only a server provides: session beans and CDI scopes become Spring components (session
beans transactional, as the container made them), `persistence.xml`, `web.xml` and the server's descriptors
become `application.properties` and a configuration class, and the build gets Spring Boot's starters in place
of the platform API. The application keeps the address the server published it under. A test that the whole
application starts is added. Timers, message-driven beans, CDI producers and events, and interceptors go to AI
with the mapping written out; Faces pages, security domains and the database connection are listed for a person.

**Ant projects, and projects without a build file.** Renova gives the migrated copy a Maven build in the
standard layout (`src/main/java`, `src/test/java`, `src/main/webapp`), read from `build.xml` or from the
folders such projects use. Each jar the project carried becomes a declared dependency: the published library
when the jar names its coordinates or Maven Central has a file with the same checksum, so that upgrades can
change its version, and otherwise the same file in a repository folder inside the project (`renova-libs`).
Then the project is migrated like any other. The original is never changed.

**Kotlin.** Kotlin sources in a Maven or Gradle build are rewritten by the same recipes, and the Kotlin compiler
(in Gradle, every Kotlin plugin) is moved to one that knows the target Java release. Groovy and Scala sources are read when a project is assessed.

**Frameworks that ended before Jakarta EE** (Struts 1, EJB 2.x home interfaces and entity beans, JAX-RPC and
Axis 1, Jersey 1, Faces managed beans, RichFaces, Seam 2, iBATIS 2, Hibernate's legacy Criteria, Commons
HttpClient 3, Quartz 1, CORBA, applets, and code tied to one server's own classes) have no recipe that carries
code across. Renova finds them, plans them, and gives AI, or a person where a build cannot check the result,
the mapping to their successor. Two of these paths have been run end to end with AI and compared with the
original over HTTP: Jersey 1 with Commons HttpClient 3, and Struts 1 to Spring MVC, where the actions, the form
bean, the pages and `web.xml` change in one request
([results](docs/verified-migrations.md#with-ai)).

The Jakarta targets also move Hibernate (to 6.6 with Jakarta EE 10, to 7.1 with Jakarta EE 11) and the
namespaces of Faces pages. Every target replaces Mockito 1 to 4 with Mockito 5, because the older ones do not
run on Java 21.

**Add-ons** are optional library upgrades added to a target with `+` (`--playbook java-to-21+junit5+log4j2`, or
`--with junit5,log4j2`; switches on the project page in the console and the desktop app):

| Add-on | Does |
|---|---|
| `junit5` | JUnit 4 → JUnit 5 (Jupiter), and a Surefire that runs it |
| `mockito5` | Mockito 1 to 4 → 5 (already part of every target; here for a project staying on its Java level) |
| `log4j2` | Log4j 1 → Log4j 2 API; the configuration file is listed for a person |
| `commons-lang3` | Apache Commons Lang 2 → 3 |
| `commons-collections4` | Apache Commons Collections 3 → 4 |
| `httpclient5` | Apache HttpClient 4 → 5 |
| `struts7` | Struts 2 → 7 (suggested with the Jakarta target for Struts projects), with the renamed `*Aware` setters followed into the code that calls them |

A build that passes without running the tests is reported as failed, and so is one that leaves a test class
out (JUnit 4 tests beside new JUnit 5 ones, for example): a migration that silently stops tests from running
has not been verified.

Maven and Gradle builds are both supported: recipes run through the project's own wrapper (`mvnw`, `gradlew`)
when it has one, and Gradle builds are changed without adding anything to their build files. Build-file
guards fix what recipes leave behind: most are for Maven (`maven` fixes). The Gradle ones (`gradle` fixes)
declare an API the migrated code imports, move the Kotlin plugins, give a Micronaut 4 build its validation
module, processor and SnakeYAML, and move the wrapper to a Gradle the Quarkus 3 plugin runs on.

**JDKs.** Renova finds the JDKs installed on the machine (`JAVA_HOME`, `~/.jdks`, `~/.sdkman`, `/usr/lib/jvm`,
the usual folders on macOS and Windows, and any folder listed in `RENOVA_JDKS`) and uses the right one for each
step: the target's JDK to build the result, and, for a Gradle project whose wrapper is too old to start on it,
an older one to run the recipes. Such a wrapper is then moved to a Gradle that runs on the target (8.14, or 9.1
for Java 25). A target for which no JDK is installed is reported before anything is changed.

Playbooks are made of **rule packs** (`include:`), so each concern is written once: the JDK's removed APIs,
Jakarta EE 10, Spring Framework 6, what Spring Boot 3 changed, servlet containers, and guards on the build
files. A playbook's own rules come first and replace an included rule with the same id. Your own playbook can
include the bundled packs and add your organisation's conventions:

```yaml
id: acme-java-21
ecosystem: java
include:
  - classpath:playbooks/java/packs/jdk-removed-apis.yaml
  - classpath:playbooks/java/packs/maven-build-guards.yaml
  - acme-conventions.yaml          # a pack of your own, beside this file
rules:
  - id: java-level
    category: A
    detect: { type: javaVersionBelow, version: 21 }
    fix: { strategy: recipe, recipes: [org.openrewrite.java.migrate.UpgradeToJava21] }
```

### .NET targets

| Playbook | Takes | To | Suggested for |
|---|---|---|---|
| `dotnet-to-10` | C# and Visual Basic projects on .NET Framework, .NET Core, or .NET 5 to 9 | .NET 10 | Every .NET project |
| `dotnet-to-8` | The same projects | .NET 8 | Chosen by hand |

What these do today, with no AI, verified with `dotnet build` and `dotnet test`
([results](docs/verified-migrations.md#net)):

- **Project files.** A project in the format from before the .NET SDK becomes an SDK-style one that compiles
  the same files: `packages.config` becomes package references, a source file on disk that the project never
  compiled is kept out, linked files, embedded resources, copied content and Visual Basic's options and
  project-wide `Imports` are carried over.
- **Target framework.** `net48`, `netcoreapp3.1`, `net6.0` and the rest move to the target; a Windows Forms or
  WPF project becomes `net10.0-windows`; .NET Standard libraries are left alone.
- **What .NET Framework had built in.** Where the code uses `System.Configuration`, `System.Data.SqlClient`,
  `System.Drawing`, WCF clients and others, a guard adds the NuGet package that now supplies it; the code is
  not touched.
- **Tests.** NUnit, xUnit and MSTest (including the MSTest that came with Visual Studio, and NUnit kept as a
  file beside the code) get the packages `dotnet test` needs. Tests written for NUnit 2 are rewritten for
  NUnit 3: `[ExpectedException(typeof(X))]` becomes `Assert.Throws<X>` around the test's body. A build that
  passes without running the project's tests is reported as failed.
- **ASP.NET Core and Entity Framework Core** packages follow the target (`10.0.*`).

**ASP.NET MVC 5 and Web API 2 to ASP.NET Core, with AI.** A project on System.Web is moved as one change:
controllers, Razor views, routes, `Global.asax`, `App_Start` and `Web.config` together, with `Program.cs`,
`appsettings.json` and `_ViewImports.cshtml` added and the files only System.Web read removed. The build and
the solution's tests check the result ([one run](docs/verified-migrations.md#with-ai)).

Found and planned, not changed: Web Forms and WCF services hosted in IIS are left for a person with the
mapping written out; BinaryFormatter and JavaScriptSerializer go to AI (not yet run); WCF hosting, Remoting,
`Thread.Abort` and code-page encodings are listed for a person.

**Windows Forms and WPF** projects (C# and Visual Basic, including Visual Basic's application framework)
become `net10.0-windows` projects, and the tests that refer to them follow. They compile on any system with
Microsoft's Windows targeting pack, which Renova turns on by itself; their tests and the applications run on
Windows only, and the report says so when a migration is verified elsewhere. What still has to be shown on a
Windows machine, and how, is in [docs/windows-test-plan.md](docs/windows-test-plan.md).

### PHP targets

| Playbook | Takes | To | Suggested for |
|---|---|---|---|
| `php-to-8.3`, `php-to-8.4`, `php-to-8.5` | Any Composer project, from PHP 5.4 on, or a site without Composer | That PHP version, and nothing else: frameworks stay | Projects without Laravel (`php-to-8.4`) |
| `laravel-11`, `laravel-12`, `laravel-13` | A Laravel application on any older Laravel | That Laravel, on PHP 8.3 (Laravel 11) or 8.4 | Laravel applications (`laravel-13`) |
| `symfony-6.4`, `symfony-7.4` | A Symfony application on Symfony 4, 5 or 6 | That Symfony (both long-term-support releases), on PHP 8.3 or 8.4 | Symfony applications (`symfony-7.4`) |

What these do today, with no AI ([results](docs/verified-migrations.md#php)):

- **composer.json.** The PHP version the project requires; `laravel/framework` and the packages released
  with it, to constraints that can reach a release for the target; packages the framework took in
  (`fruitcake/laravel-cors`, `fideloper/proxy`) or that were renamed (`facade/ignition`, `fzaninotto/faker`);
  a PHPUnit that runs there.
- **Code.** Rector's rules for every PHP release up to the target (removed functions and syntax, deprecated
  forms) and for every Laravel release up to the target, and the PHPUnit changes for the version installed.
  A file Rector cannot read as PHP is left as it is and named in the report.
- **Verification on the target PHP.** `composer update` resolves and installs what `composer.json` now asks
  for, every file of the project is checked to parse, and the project's tests run with PHPUnit or Pest. PHP
  has no compiler to catch the rest, so a project without tests is reported as unproven.

Rector's rule sets also move code to newer idioms (constructor promotion, arrow functions, and in Laravel 13
attributes in place of `$fillable`): more is changed than the target strictly needs, and the tests are what
shows that nothing else did. Closure factories from Laravel 7 and older become factory classes without AI.
What PHP 8 runs differently from PHP 7 (arithmetic on text, loose comparisons) no rule can decide: the tests
show it and the AI repair loop fixes it ([one run](docs/verified-migrations.md#with-ai)). A site without
Composer (PHP files in a folder, as they were uploaded) gets a `composer.json` in the migrated copy and is
migrated like any other; it usually has no tests, so `--verify-behaviour` is what shows it still works. Not
yet done: the `mysql_*` functions (planned for AI, not run), and frameworks other than Laravel and Symfony.

For Symfony: every `symfony/*` package released with the framework and the version Flex holds them to,
annotations as attributes (routes, validation, Doctrine mapping), SensioFrameworkExtraBundle out of
`composer.json`, `bundles.php` and its settings with `@Method` merged into the route, and Rector's Symfony
rules for each release on the way. Configuration keys a release removed (security, session) are left to the
build's errors and AI repair, with what changed written out for it.

`--with phpunit11` (or `php-to-8.4+phpunit11`) goes on from PHPUnit 9 to 11: attributes in place of
annotations, and a `phpunit.xml` in the form PHPUnit 10 and later read. `--verify-behaviour` runs the original
and the migrated application side by side in Docker and compares their answers, as for Java.

## Extending Renova

All extension points are Java interfaces discovered with `ServiceLoader`. Adding a jar to the
classpath is enough.

| Extension point | Purpose |
|---|---|
| `EcosystemPlugin` | Project model, detectors, fixers, verifier and bundled playbooks for one technology stack |
| `DetectorFactory` | A new `detect.type` for playbooks |
| `Fixer` | A new fix strategy |
| `Verifier` | Proves a migrated workspace builds (and, later, behaves the same) |
| `AiProviderFactory` | Any model, hosted or on-premises, created per run from the caller's own settings and key |

`engine/core/src/test/java/io/renova/core/EngineTest.java` implements a complete toy ecosystem in about
twenty lines and is a good starting point. See [`engine/README.md`](engine/README.md) for details.

## Deploying

Renova runs on machines the customer controls; it is not a hosted service and needs no Docker. A version tag
(`git tag v0.2.0 && git push origin v0.2.0`) builds and publishes a GitHub release with the desktop installers for
Linux, Windows and macOS, the web bundle, the CLI and both IDE plugins. See [docs/deployment.md](docs/deployment.md).

## Licensing

A released build assesses any project without a licence and needs one to migrate. A licence is a signed file,
checked on the customer's machine with no call to any server; it names the licensee, the ecosystems it covers
and its last day. `renova licence install FILE`, or **Settings → Licence** in the desktop app. A build from
source migrates without one. See [docs/licensing.md](docs/licensing.md).

## Roadmap

1. **RAG:** phase 1 (structural code retrieval and curated knowledge, no key needed) is in and on by default.
   Next: lessons from accepted fixes, then optional embeddings on the customer's own key.
2. **Behavioural verification:** phases 1–3 (`--verify-behaviour`: HTTP answers, scenario files, database changes,
   AI repair of differences) and the Spring Boot runner are in. Next: a JBoss/WildFly runner, Gradle builds, recorded-traffic replay
   ([design](docs/behavioural-verification-design.md)).
3. **Benchmark harness:** `renova benchmark` scores migrations of synthetic legacy apps (see
   [`benchmark/`](benchmark)). Next: more apps, including public open-source legacy projects.
4. **Web console:** single sign-on (the audit log and local mode are in). **Licensing** is in: signed licence
   files checked offline. **Desktop:** signed installers need certificates that are not bought yet
   ([what and how](docs/deployment.md#signing-the-installers)).
5. **More targets and ecosystems:** Java 17/21/25, Spring Boot 3 and 4.1, Spring Framework 7 with Jakarta EE 11,
   Micronaut 4, Quarkus 3, Hibernate 6 and 7, Struts 7, library add-ons, Gradle builds, Ant builds, Kotlin and
   Java EE → Spring Boot are in, with Gradle builds of Kotlin, Micronaut and Quarkus projects. Next for Java:
   re-platforming a Gradle build, multi-module Ant builds, AI runs of the remaining legacy frameworks (EJB 2,
   JAX-RPC, Faces managed beans, iBATIS).
6. **.NET:** .NET Framework, .NET Core and older .NET → .NET 10 or 8 is in for C# and Visual Basic class
   libraries, console applications, test projects and ASP.NET Core applications, with NUnit 2 → 3 and, with
   AI, ASP.NET MVC 5 and Web API 2 → ASP.NET Core. Next: a larger real MVC application, Entity Framework 6 →
   EF Core, Windows Forms and WPF verified on Windows, behavioural verification for ASP.NET Core (the original
   needs IIS on Windows), Web Forms.
7. **PHP:** a chosen PHP version (8.3, 8.4, 8.5), Laravel version (11, 12, 13) and Symfony version (6.4, 7.4)
   are in, with sites without Composer, a PHPUnit add-on and behavioural verification. Next: an option to
   change only what the target requires, a real Laravel or Symfony application with a database and security
   configuration, `mysql_*` with AI, Symfony 8.

## Contributing

- Branch from `main` using a descriptive prefix: `feature/…`, `fix/…`, `docs/…`.
- Write commit messages in [Conventional Commits](https://www.conventionalcommits.org/) style,
  for example `feat(engine): add Gradle recipe runner`.
- Run `mvn verify` before opening a pull request. All tests must pass.
- Never commit secrets. Configuration such as API keys comes from the environment or a local `.env`,
  which git ignores.

## License

Copyright © 2026 Eselase Noble. All rights reserved.

This software is proprietary. No licence is granted to use, copy, modify or distribute it without
written permission from the copyright holder.
