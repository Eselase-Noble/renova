# Renova Engine

Assess and migrate legacy systems using **declarative playbooks**, **pluggable ecosystems**, and an
**AI repair loop** that is checked by the real build. Java is the first ecosystem. The engine itself
is not tied to Java.

```
analyze ─► plan ─► migrate (copy) ─► recipe ─► replace ─► ai ─► verify ─► ai-repair ─► report
                     │                  every stage = one git commit in the workspace
                     └─ the source project is never modified
```

## Build

From the repository root (JDK 21+):

```sh
mvn install        # builds and tests renova-core and renova-java, among the other modules
```

The engine is a library. The products in this repository (`cli`, `web`, `desktop`, `ide`) depend on it.

## Concepts

| Concept | What it is | Where |
|---|---|---|
| **Playbook** | YAML: target platform plus rules. New migration paths are data, not code | `java/src/main/resources/playbooks/` |
| **Rule** | `detect` (what to find) + `fix` (who resolves it) + category A–E + severity | in a playbook |
| **Detector** | A `detect.type`. Core: `fileExists`, `fileContains`. Java: `import`, `dependency`, `javaVersionBelow` | `DetectorFactory` SPI |
| **Fix strategy** | `recipe` (OpenRewrite), `replace` (text, for JSP/TLD/config), `ai`, `manual` | `Fixer` SPI |
| **Ecosystem plugin** | Project model, detectors, fixers, verifier and bundled playbooks for one stack | `EcosystemPlugin` SPI (ServiceLoader) |
| **AI provider** | Any model (hosted or on-prem) that proposes whole-file edits, created from the caller's own settings | `AiProviderFactory` SPI (ServiceLoader) |
| **Verifier** | Builds the workspace and turns failures into structured errors for the repair loop | `Verifier` SPI |

Change categories: **A** build/descriptors, **B** namespace renames, **C** API changes,
**D** runtime/container behaviour, **E** dependency declarations. Steps run in the order A, E, B, C, D.

The plan reports an **automation rate**: the share of findings resolved by deterministic fixers.
This is the headline number for customers.

## Modules

- `core` (`renova-core`): ecosystem-neutral engine (model, playbooks, SPI, analyzer, planner, migrator, workspace, AI loop, reports)
- `ai-anthropic` (`renova-ai-anthropic`): Claude provider (official Anthropic Java SDK, JSON-schema responses, streaming, refusal handling)
- `ai-openai` (`renova-ai-openai`): OpenAI provider (official OpenAI Java SDK, strict JSON-schema responses, streaming); works with OpenAI-compatible servers through `openai.baseUrl`
- `java` (`renova-java`): Java plugin (Maven/Gradle model, Java detectors, OpenRewrite fixer, Maven verifier, Java 8→21/Jakarta playbook)

## Adding an ecosystem

Implement `EcosystemPlugin`, register it in
`META-INF/services/io.renova.core.spi.EcosystemPlugin`, and ship playbooks.
`EngineTest` builds a toy "text" ecosystem in about 20 lines, which shows the minimum needed.

## Status and roadmap

Working now: analysis, planning, a safe workspace with per-stage commits, OpenRewrite recipes,
text replacement, Maven build verification with structured errors, the AI fix and repair loop
with a Claude provider, and Markdown/JSON reports.

Next:
1. **More AI providers**: an on-premises option.
2. **Post-migration guard rules**: checks that run on the migrated code, e.g. the servlet API must stay `provided` scope (the Jakarta recipe currently changes it to `compile`).
3. **Behavioural verification**: run the old and new apps side by side and diff HTTP responses and DB effects.
4. **Benchmark harness**: score the tool against public legacy projects with known migrated versions.
5. **Category E detector**: classes used in code but only available through transitive dependencies.
6. Gradle recipe runner, `pom.*.xml` variant handling, more playbooks (Spring Boot 2→3, Java EE→Quarkus, then .NET/Python).
7. Licensing, a playbook marketplace and private playbook packs.
