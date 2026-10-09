# Deploying Renova

Renova is never a hosted service. Every part runs on a machine the customer controls, and project code and
migrated copies stay there; only AI requests leave it, to the provider and on the key the customer sets. Nothing
here needs Docker (it is used only by the optional behavioural verification, on the machine doing the migration).

## What a release contains

Pushing a version tag builds every part at that version and attaches it to a GitHub release
([`.github/workflows/release.yml`](../.github/workflows/release.yml)):

| File | What it is | Needs on the target machine |
|---|---|---|
| `Renova-VERSION.msi`, `Renova-VERSION-arm64.dmg`, `Renova-VERSION-x64.dmg` | **Desktop app** for Windows and for macOS on Apple Silicon and on Intel, with its own Java runtime (on macOS a version below 1 is written 1.x) | Nothing. Git and Maven to migrate projects |
| `renova_VERSION_amd64.deb`, `renova-VERSION-1.x86_64.rpm`, `renova-VERSION-1-x86_64.pkg.tar.zst`, `renova-VERSION-linux-x86_64.tar.gz` | **Desktop app** for Linux: Debian and Ubuntu, Fedora and RHEL and openSUSE, Arch, and any distribution (unpack and run `Renova/bin/Renova`) | Nothing. Git and Maven to migrate projects |
| `renova-web-VERSION.tar.gz` | **Web API and console**, a launcher, systemd units and a guide | Java 21+, Node.js 20+ |
| `renova-cli-VERSION.zip` | **Command line** for terminals and CI pipelines | Java 21+ |
| `renova-VERSION.vsix` | **VS Code extension** (the CLI is bundled) | Java 21+ |
| `renova-intellij-VERSION.zip` | **IntelliJ IDEA plugin** (2025.2+) | Nothing |
| `SHA256SUMS.txt` | Checksums of all of the above | |

Starting the desktop app after installing it: the Start menu or the desktop shortcut on Windows; Launchpad or
the Applications folder on macOS; the applications menu (Development) on Linux, or `/opt/renova/bin/Renova`,
`renova-desktop` on Arch, and `Renova/bin/Renova` from the unpacked tarball.

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

## Signing the installers

Unsigned installers work and are met with a warning: Windows SmartScreen says "Windows protected your PC", and
macOS Gatekeeper refuses to open the application until the user allows it in System Settings. Both go away
with a certificate in the vendor's name. Neither is bought or set up yet.

| System | What to buy | What is in place |
|---|---|---|
| macOS | An Apple Developer Program membership (99 USD a year) and a *Developer ID Application* certificate from it | `desktop/package.sh` signs when `MAC_SIGNING_IDENTITY` names the certificate in the keychain. Notarising the `.dmg` (`xcrun notarytool submit --wait`, then `xcrun stapler staple`) is a step still to add to the release workflow |
| Windows | A code-signing certificate. Since 2023 these are kept on a hardware token or in a cloud service, not in a file; the least effort is Microsoft's Trusted Signing (an Azure subscription, about 10 USD a month, and identity validation that takes days) | Nothing: the `.msi` from `jpackage` is signed afterwards with `signtool` and the service's plug-in, a step to add to the release workflow once the account exists |
| Linux | Nothing | `.deb` packages are not signed individually |

Until then, tell customers on Windows to choose **More info → Run anyway**, and on macOS to open the
application once from **System Settings → Privacy & Security**; the checksums in the release let them check
that the file is the one that was published.

## Licences

A released build assesses projects freely and asks for a licence before it migrates one; see
[licensing.md](licensing.md). A build from source (a `-SNAPSHOT` version, including an installer made by hand
with `desktop/package.sh`) does not ask.

## Single sign-on for the web console

The server edition signs people in with a password, or through your identity provider over OpenID Connect
(Microsoft Entra ID, Okta, Google Workspace, Keycloak, anything that speaks it). SAML is not supported.

1. Set Renova up as usual: the first account and organisation are made with a password. That account stays
   the way in if the provider is ever unreachable.
2. Register Renova with the provider as a web application with the redirect address
   `<public address of the console>/api/auth/sso/callback/sso`, and let it release the `email` claim.
3. Give the API these settings (in `application.properties`, or as environment variables such as
   `RENOVA_SSO_CLIENT_ID`):

   | Setting | What it is |
   |---|---|
   | `renova.public-url` | The address people open the console at, for example `https://renova.example.com` |
   | `renova.sso.issuer` | The provider's issuer address; the rest is discovered from it the first time someone signs in |
   | `renova.sso.client-id`, `renova.sso.client-secret` | What the provider gave the registration |
   | `renova.sso.name` | The name on the button: "Sign in with …" |
   | `renova.sso.allowed-domains` | Email domains whose people get in without an invitation; they join the first organisation |
   | `renova.sso.role` | The role those people get: `viewer`, `member` (the default) or `admin` |
   | `renova.sso.only` | `true` refuses passwords, so everyone signs in through the provider |

The provider says who someone is; Renova decides whether they get in. Someone signs in if they have an
account, if an invitation is waiting for their email address (it is used up, with the role it gave), or if
their address is in an allowed domain. Anyone else the provider knows is sent back to the sign-in page and no
account is made. Roles are managed in Renova and are not read from the provider's groups. Sign-ins through
the provider are in the audit log. Local mode has no sign-in at all, so none of this applies to it.

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
