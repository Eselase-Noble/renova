# RAG design: retrieval for AI-assisted migration

**Status:** phase 1 implemented (off by default, `--rag`); phases 2–4 proposed
**Scope:** `engine/core` (contracts, assembler), ecosystem plugins (code retrieval), new `engine/rag-*` modules

## 1. Problem

An AI request today contains the target files, their build file (from `relatedFiles`), the matched
rules and the build errors. That fixes problems confined to one file and its build file. It is not
enough when the fix depends on code or knowledge the request does not contain:

| Missing context | Example |
|---|---|
| Project code the target depends on | A controller extends a base class that also used the removed `HandlerInterceptorAdapter` |
| Configuration that refers to the code | A Spring XML file declares the `CommonsMultipartResolver` bean the rule asks to replace |
| Migration knowledge | Spring 6 URL-matching changes; Tomcat 10.1's `suspendWrappedResponseAfterForward` |
| Fixes already made in this migration | The same missing dependency fixed in another module an hour ago |

Retrieval-augmented generation (RAG) adds the most relevant of this to each request, within a token
budget, without changing the safety model.

## 2. Requirements

1. **Bring your own key.** Any model call, including embeddings, uses the customer's own credentials.
   Renova never supplies or pools keys.
2. **Works without embeddings.** Anthropic has no embeddings API, and some customers allow no
   external calls at all. Retrieval must be useful with no embedding provider configured.
3. **Customer data stays isolated.** Indexes live in the migration workspace (CLI) or in per-tenant
   storage (server). Customer code never enters shared knowledge.
4. **Safety model unchanged.** Retrieved files are reference-only. Only files an ecosystem plugin
   marks as related-and-editable can be changed (as today).
5. **Measurable.** RAG is enabled only if it raises build-pass rates or cuts repair rounds or tokens
   on the benchmark apps.
6. **Transparent cost.** Embedding and retrieval token usage is reported alongside generation usage.

## 3. Approach: structure first, then text, embeddings optional

For code, *structural* retrieval (following imports, type references and configuration references)
is more precise than semantic similarity and costs nothing. Embeddings help mainly with natural-language
knowledge (migration guides, lessons). So retrieval runs three channels and fuses their results:

| Channel | Finds | Needs a key? |
|---|---|---|
| **Structural** (ecosystem plugin) | Project types the target imports, extends or implements; files that reference the target's types (Spring XML, JSP, descriptors) | No |
| **Lexical** (BM25) | Code and knowledge sharing identifiers or error text, e.g. `CommonsMultipartResolver` or `package jakarta.annotation does not exist` | No |
| **Semantic** (embeddings, optional) | Knowledge with the same meaning in different words | Yes: customer's OpenAI key, or a local OpenAI-compatible embedding server |

## 4. Architecture

```
                        ┌───────────────────────── RetrievalQuery ─────────────────────────┐
                        │ target files, build errors, matched rules, playbook, token budget│
                        └───────────────┬───────────────────────────────┬──────────────────┘
                                        ▼                               ▼
      ┌──────────────────┐   ┌──────────────────┐   ┌────────────────────────────┐
      │ StructuralRetriever│ │ LexicalRetriever  │   │ SemanticRetriever (opt.)   │
      │ (EcosystemPlugin)  │ │ BM25 over index   │   │ vectors via EmbeddingProvider│
      └─────────┬────────┘   └─────────┬────────┘   └──────────────┬─────────────┘
                └──────────────┬───────┴───────────────────────────┘
                               ▼
                     ContextAssembler: rank fusion, de-duplication,
                     token budget, roles (REFERENCE unless plugin says editable)
                               ▼
                     FixRequest.files + FixRequest.knowledge  ──►  AiProvider
```

### 4.1 Contracts (in `engine/core`)

```java
public interface Retriever {
    String name();
    List<ContextItem> retrieve(RetrievalQuery query);
}

public record ContextItem(String source,      // "code:src/.../Base.java", "knowledge:spring-6/url-matching"
                          String content,
                          Kind kind,          // CODE or KNOWLEDGE
                          double score,
                          String why) { }      // shown to the model, like RelatedFile.why

public interface EmbeddingProvider extends AutoCloseable {   // created from caller settings, like AiProviderFactory
    String name();
    float[][] embed(List<String> texts);
    long tokensUsed();
}
```

`FixRequest` gains a `knowledge` list (text snippets, never editable). Retrieved code files join
`files` with `Role.REFERENCE`.

### 4.2 Structural retrieval (per ecosystem)

`EcosystemPlugin` gains `default List<RelatedFile> referencedFiles(ProjectModel, String file)`.
For Java:

- Resolve the target's imports and `extends`/`implements` clauses to project source files (an index
  of fully qualified names to files, built during analysis).
- Find files that mention the target's fully qualified type names: Spring XML, `web.xml`, JSP, TLD,
  `.properties`.
- Rank: direct supertypes first, then imported project types, then configuration references.

Parsing uses the same machinery as analysis (OpenRewrite's syntax trees or a lightweight parser), so
there is no new dependency.

### 4.3 Index

**Apache Lucene**, embedded: BM25 and vector (HNSW) search in one pure-Java library, so no database
server and it works offline.

- **CLI:** index in the workspace at `.renova/index/`, built after the baseline commit and updated
  after each stage commit, so retrieval sees code as already migrated.
- **Server:** one index per tenant and project on tenant-scoped storage. pgvector is an alternative
  if the web product standardises on PostgreSQL.
- **Chunking:** code by type and method, with the file path and type name in every chunk; knowledge
  cards whole (they are short by design).

### 4.4 Knowledge base

Two kinds, kept strictly apart:

1. **Curated knowledge, shipped with playbooks.** Markdown "knowledge cards" written by Renova
   engineers from public sources (framework migration guides, container release notes) and
   experience. Each card has an id, applicable playbooks, trigger terms and a short body, for
   example *"`package jakarta.annotation does not exist` after javax→jakarta → declare
   `jakarta.annotation:jakarta.annotation-api`; Java 8 used to provide `javax.annotation`"*. Cards
   are versioned with the playbook and are a sellable asset, like playbooks.
2. **Lessons, per organisation.** When an AI edit is accepted (the build passes after it), record
   the error, the diff and the rationale as a lesson in that organisation's store. Later requests
   in the same migration, or in later migrations by the same customer, can retrieve it. Lessons are
   opt-in and never leave the tenant.

### 4.5 Context assembly

- **Fusion:** reciprocal rank fusion across channels, so no channel's score scale dominates.
- **Budget:** a configurable share of the request (default 30% of `MAX_REQUEST_CHARS`). Target and
  editable related files always come first; retrieved items fill the remaining space by rank.
- **Prompt caching:** knowledge cards are stable across requests in one migration. They go in a
  separate system block with a cache breakpoint (Anthropic) to cut repeated input cost.
- **Explainability:** the report lists which context items each AI edit received.

### 4.6 Configuration (bring your own key)

| Setting | Values | Default |
|---|---|---|
| `rag.enabled` | `true` / `false` | `true` once benchmarks justify it |
| `rag.embeddings.provider` | `none`, `openai` | `none` |
| `rag.embeddings.model` | model id | provider default |
| `rag.embeddings.baseUrl` | URL of an OpenAI-compatible embedding server (e.g. local) | `openai.baseUrl` |
| `rag.embeddings.apiKey` | the customer's key | reuses `openai.apiKey` |
| `rag.lessons` | `off`, `migration`, `organisation` | `migration` |
| `rag.budget` | share of the request for retrieved context | `0.3` |

An Anthropic-only customer gets structural, lexical and knowledge retrieval with no extra key. Adding
an OpenAI key, or a local embedding server, enables the semantic channel.

## 5. Evaluation

Measured on the synthetic apps in `~/Projects/renova-test-apps` (to be extended with harder cases),
for each configuration: no RAG; structural + lexical; plus embeddings.

| Metric | Why |
|---|---|
| Build passes after migration (%) | The headline outcome |
| Repair rounds to a passing build | Speed and cost |
| Input and output tokens per migration | Cost to the customer |
| Rejected or declined edits | Safety and quality signal |
| Behaviour-check pass rate (once behavioural verification exists) | Correctness, not just compilation |

Each test app has a known-good migrated version, so results are scored automatically.

## 6. Delivery plan

| Phase | Delivers | Needs a key? |
|---|---|---|
| 1 | Contracts, context assembler, Java structural retrieval, curated knowledge cards with lexical search, report of context used | No |
| 2 | Lessons store (per migration, then per organisation) | No |
| 3 | Embedding providers (OpenAI, OpenAI-compatible local) and the semantic channel | Customer's own |
| 4 | Server storage: per-tenant indexes, lesson management in the web console | — |

Phase 1 gives most of the value for code migration and works for every customer, so it comes first.

**Phase 1 as built.** The contracts (`Retriever`, `ContextItem`, `RetrievalQuery`), the `ContextAssembler`
(reciprocal rank fusion, budget) and the knowledge retriever are in `engine/core` (`io.renova.core.rag`).
Java structural retrieval is `JavaPlugin.referencedFiles`. Differences from the proposal above:

- Knowledge cards are inline in the playbook YAML (`knowledge:`), so they load the same way from the
  classpath and from a customer's playbook file.
- Lexical search is an in-memory BM25 over the cards, not Lucene: the corpus is small. A card is used when
  one of its triggers appears in the request, or when its BM25 score alone is high. Lucene arrives with
  code-level lexical search and the semantic channel.
- Knowledge notes go in the user message, not a cached system block, because each request gets a different
  selection. Prompt caching can follow once a migration's cards are sent as one stable block.
- The context each request received is listed in its AI audit log (`.renova/ai/NNN.md`), not yet in
  `report.md`.

## 7. Open questions

1. Should curated knowledge cards be included in all editions, or sold as playbook packs?
2. Should lessons be on by default per organisation, or opt-in per project?
3. For the web product: Lucene per tenant, or PostgreSQL with pgvector?
4. Which harder synthetic test apps come next (e.g. multi-module Maven, Spring XML-heavy, EJB)?
