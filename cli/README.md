# Renova CLI

Command-line interface to the [Renova engine](../engine), for terminals and CI pipelines.

## Build

```sh
(cd ../engine && mvn install)   # until the engine is published to a registry
mvn package                            # produces target/renova.jar
```

## Use

```sh
cli/bin/renova playbooks                                  # installed ecosystems and playbooks
cli/bin/renova analyze <project> [-f md|json] [-o FILE]   # read-only assessment and plan
cli/bin/renova migrate <project> --out <dir>              # migrate a copy; each stage is a git commit
          [--playbook ID|FILE] [--maven-settings FILE] [--offline]
          [--ai PROVIDER] [--ai-model MODEL] [--ai-effort LEVEL] [--env-file FILE]
          [--skip recipe,ai] [--no-verify] [--skip-tests] [--max-ai-iterations N]
cli/bin/renova config show | set KEY VALUE | set-key PROVIDER | unset KEY | check
```

See "Configure AI" in the [main README](../README.md#configure-ai-bring-your-own-key) for how API keys are supplied.

`migrate` writes `.renova/report.md` and `.renova/report.json` into the output directory. It exits
with 0 when the migrated build passes, 1 when it fails, and 2 on usage errors, so CI can gate on it.
Set `RENOVA_DEBUG=1` for stack traces.
