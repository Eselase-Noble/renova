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
| **Detector** | A `detect.type`. Core: `fileExists`, `fileContains`. Java: `import`, `dependency`, `javaVersionBelow`, `mavenPluginBelow`, `pomProperty`, `importWithoutDependency`. .NET: `targetFrameworkBelow`, `legacyProjectFormat`, `projectKind`, `nugetPackage`, `namespace`, `namespaceWithoutPackage`, `assemblyReference` | `DetectorFactory` SPI |
| **Fix strategy** | `recipe` (OpenRewrite), `replace` (text, for JSP/TLD/config), `maven` (pom.xml edits), `gradle` (build.gradle and wrapper edits), `ai`, `manual` | `Fixer` SPI |
| **Guard** | A rule with `phase: guard`, checked on the migrated code before verification | in a playbook |
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
- `php` (`renova-php`): PHP plugin (Composer project model, `phpVersionBelow` and `composerPackage` detectors, the `composer` and `rector` fixers, a verifier that runs Composer, a syntax check and PHPUnit or Pest on the target PHP, PHP → 8.3/8.4/8.5 and Laravel → 11/12/13 playbooks)
- `dotnet` (`renova-dotnet`): .NET plugin (C# and Visual Basic project model in both project formats, .NET detectors, the `dotnet` project-file fixer, `dotnet build`/`dotnet test` verifier, .NET → 10 and → 8 playbooks)
- `java` (`renova-java`): Java plugin (Maven/Gradle model, Java detectors, OpenRewrite fixer, Maven verifier, Java 8→21/Jakarta playbook)

## Adding an ecosystem

Implement `EcosystemPlugin`, register it in
`META-INF/services/io.renova.core.spi.EcosystemPlugin`, and ship playbooks.
`EngineTest` builds a toy "text" ecosystem in about 20 lines, which shows the minimum needed.

## Status and roadmap

Working now: analysis, planning, a safe workspace with per-stage commits, OpenRewrite recipes for Maven and
Gradle builds, text replacement, format-preserving pom.xml and build.gradle edits, guard rules (checked after
the fix stages and after each AI repair round), Maven and Gradle build and test verification with structured
errors, the AI fix and repair loop with Claude and OpenAI-compatible providers (including on-premises
servers), whole-layer AI requests for framework replacements, retrieval (RAG phase 1, `--rag`), behavioural
verification of servlet-container WARs and Spring Boot applications, an AI audit log, Markdown/JSON reports,
and the benchmark harness. The targets and what has been verified are in the
[root README](../README.md#targets) and [docs/verified-migrations.md](../docs/verified-migrations.md).

Next:
1. **Behavioural verification**: a JBoss/WildFly runner, Gradle builds, recorded-traffic replay.
2. **More benchmark apps**, including more public open-source legacy projects.
3. **RAG phase 2**: lessons from accepted fixes, per migration and per organisation.
4. **Java**: re-platforming a Gradle build, multi-module Ant builds, AI runs of the remaining legacy frameworks.
5. **More ecosystems**: .NET and PHP have their first targets (see the root README).
6. Licensing, a playbook marketplace and private playbook packs.
