# Renova benchmark

Measures what Renova achieves on legacy apps with known correct outcomes, so changes to playbooks, guards,
prompts or retrieval are judged by results rather than impressions.

```sh
cli/bin/renova benchmark --out /tmp/bench                        # every app, every configuration
cli/bin/renova benchmark --out /tmp/bench --configs deterministic # free: no AI
cli/bin/renova benchmark --out /tmp/bench --configs ai,ai-rag --only claims-portal --repeat 3
```

Configurations that use AI need a provider and the user's own key, set as for `migrate` (`--ai`,
`--env-file`, environment variables or `renova config`). The command says how many migrations will use it
before it starts.

## What it measures

For every app and configuration:

| Metric | Source |
|---|---|
| Build | The migrated project builds and its own tests pass on the target JDK |
| Checks | The suite's checks on the migrated copy, e.g. upload limits unchanged, no `--add-opens` workaround |
| Repair rounds | AI build-repair rounds that edited files and rebuilt |
| AI requests | Changed, unchanged and declined answers; edits rejected for touching files not offered as editable |
| Tokens | Input and output tokens, the customer's cost |
| Retrieved context | Knowledge notes and reference files sent (with RAG) |

A run *passes* when the build passes and every check holds. Results go to `results.md` (scoreboard) and
`results.json`, next to one migrated workspace per run, each with its report and AI audit log.

## Two suites

| Suite | Question it answers | AI |
|---|---|---|
| [`suite.yaml`](suite.yaml) (default) | How well does Renova do on applications whose last changes need judgement? | With and without |
| [`targets.yaml`](targets.yaml) | Does every target Renova offers work end to end? One app per path, including the public Spring PetClinic sample | Without |

```sh
benchmark/fetch-public.sh       # once: fetches PetClinic at its last Spring Boot 2.7 commit
cli/bin/renova benchmark --suite benchmark/targets.yaml --out /tmp/targets --configs deterministic
```

The latest results of the targets suite are in [docs/verified-migrations.md](../docs/verified-migrations.md).

## Suite

[`suite.yaml`](suite.yaml) lists the apps, their checks and the configurations. The apps are synthetic
legacy projects in `../renova-test-apps` (pass `--apps DIR` to use another location). Each app builds and
passes its tests on Java 8, so its tests define the behaviour a migration must keep. Renova never lets the
AI edit tests.

| Configuration | What runs |
|---|---|
| `deterministic` | Recipes, text rules and guards. Free |
| `ai` | Plus the AI provider for rule fixes and build repair |
| `ai-rag` | Plus retrieval: related project code and the playbook's knowledge cards |

AI output varies between runs: use `--repeat` before drawing conclusions from small differences.
