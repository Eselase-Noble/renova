# Renova

**Assess and modernise legacy software systems, safely and repeatably.**

Renova analyses a legacy codebase, produces a migration plan, and carries out the migration on a copy
of the project. Deterministic rewrites handle the mechanical bulk. Context-dependent changes go to an
AI model or a person, and the real build checks every change.

> **Status:** early development (`0.1.0-SNAPSHOT`). The engine and command-line interface work for
> Java projects. The web, desktop and IDE products are planned. See the [roadmap](#roadmap).

---

## Contents

- [Why Renova](#why-renova)
- [How it works](#how-it-works)
- [Repository layout](#repository-layout)
- [Getting started](#getting-started)
- [Playbooks](#playbooks)
- [Extending Renova](#extending-renova)
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
  Compiler, build-file and test failures become structured errors that feed an automatic repair loop. One repair can change a source file and its build file together.
- **Bring your own AI key.** Each user or organisation supplies its own provider credentials. Token
  usage is reported per migration.
- **Any ecosystem.** The engine has no Java-specific code. Java is the first plugin.

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
| [`engine/ai-anthropic`](engine) | AI provider for Claude, using each user's own Anthropic API key | Working |
| [`engine/ai-openai`](engine) | AI provider for OpenAI or any OpenAI-compatible server (Azure OpenAI, vLLM, Ollama), using the user's own key | Working |
| [`cli`](cli) | `renova` command for terminals and CI pipelines | Working |
| [`web`](web) | REST API and web console, hosted or on-premises | Planned |
| [`desktop`](desktop) | Offline JavaFX desktop application running the engine locally | Planned |
| [`ide`](ide) | IntelliJ IDEA and VS Code integrations | Planned |

## Getting started

### Prerequisites

- JDK 21 or later
- Maven 3.8 or later
- Git, used to version migration workspaces
- Network access to Maven Central, or a mirror configured in `settings.xml`

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

It needs Docker and currently runs single-WAR Maven applications on servlet containers. Applications that need
a full Jakarta EE server are reported as skipped. See
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
| `maven` | Format-preserving pom.xml edits: `setScope`, `setPluginVersion`, `setProperty`, `addDependency`, `setVersion`, `removeDuplicates` |
| `ai` | The configured AI provider. The build verifies the result |
| `manual` | A person, guided by the rule's `hint` in the report |

Bundled playbook: **`java8-to-21-jakarta-ee10`**, which takes Java 8 / Java EE web applications to Java 21,
Jakarta EE 10 and Spring 6, for Tomcat 10.1/11 and WildFly 27+ / JBoss EAP 8. Pass `--playbook path/to/file.yaml`
to use your own.

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

## Roadmap

1. **RAG:** phase 1 (structural code retrieval and curated knowledge, no key needed) is in and on by default.
   Next: lessons from accepted fixes, then optional embeddings on the customer's own key.
2. **Behavioural verification:** phases 1–3 (`--verify-behaviour`: HTTP answers, scenario files, database changes,
   AI repair of differences) are in. Next: JBoss/WildFly and Spring Boot runners, recorded-traffic replay
   ([design](docs/behavioural-verification-design.md)).
3. **Benchmark harness:** `renova benchmark` scores migrations of synthetic legacy apps (see
   [`benchmark/`](benchmark)). Next: more apps, including public open-source legacy projects.
4. **Web console and REST API**, then the **desktop** and **IDE** products.
5. **More playbooks and ecosystems:** Spring Boot 2 → 3, Java EE → Quarkus, then .NET and Python.

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
