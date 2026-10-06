# Deploying Renova

Renova is never a hosted service. Every part runs on a machine the customer controls, and project code and
migrated copies stay there; only AI requests leave it, to the provider and on the key the customer sets. Nothing
here needs Docker (it is used only by the optional behavioural verification, on the machine doing the migration).

## What a release contains

Pushing a version tag builds every part at that version and attaches it to a GitHub release
([`.github/workflows/release.yml`](../.github/workflows/release.yml)):

| File | What it is | Needs on the target machine |
|---|---|---|
| `renova_VERSION_amd64.deb`, `Renova-VERSION.msi`, `Renova-VERSION.dmg` | **Desktop app** for Linux, Windows and macOS, with its own Java runtime | Nothing. Git and Maven to migrate projects |
| `renova-web-VERSION.tar.gz` | **Web API and console**, a launcher, systemd units and a guide | Java 21+, Node.js 20+ |
| `renova-cli-VERSION.zip` | **Command line** for terminals and CI pipelines | Java 21+ |
| `renova-VERSION.vsix` | **VS Code extension** (the CLI is bundled) | Java 21+ |
| `renova-intellij-VERSION.zip` | **IntelliJ IDEA plugin** (2025.2+) | Nothing |
| `SHA256SUMS.txt` | Checksums of all of the above | |

## Which to give a customer

- **Desktop app** when the code must stay on the developer's own device. It is the default recommendation: one
  installer, no ports, no accounts, no server to look after.
- **Web bundle in local mode** (`bin/renova-web local`) for the same thing in a browser.
- **Web bundle in server mode** for a team that shares projects and results, on a server inside their network.
  The steps (systemd, HTTPS with a reverse proxy, settings, backup and upgrade) are in the bundle's
  [README](../web/bundle/README.md).
- **CLI** for pipelines; **IDE plugins** for developers who want findings in the editor.

## Cutting a release

```sh
mvn verify                                 # everything passes locally
git tag v0.2.0 && git push origin v0.2.0   # the Release workflow does the rest
```

The workflow sets the version from the tag (in the Maven modules, the IntelliJ plugin and the VS Code extension),
runs the tests, builds each part and publishes the release with generated notes. A tag that fails its tests
publishes nothing. On macOS an installer's version may not start with 0, so `0.3.1` is labelled `1.3.1` there.

Installers are not code-signed yet: Windows and macOS warn on first launch until signing certificates are added to
the workflow.

## Building the same things by hand

```sh
desktop/package.sh --type deb    # or msi, dmg, rpm, app-image: on that operating system
web/package.sh                   # web/target/renova-web-VERSION.tar.gz
cli/package.sh                   # cli/target/renova-cli-VERSION.zip
(cd ide/vscode && npm ci && npm run package)
(cd ide/intellij && ./gradlew buildPlugin)
```

## Continuous integration

Every push to `main` and every pull request builds and tests the Maven modules, the console, the VS Code
extension and the IntelliJ plugin ([`.github/workflows/ci.yml`](../.github/workflows/ci.yml)).
