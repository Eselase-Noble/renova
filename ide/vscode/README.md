# Renova for VS Code

Assess and migrate legacy Java, .NET and PHP projects from VS Code: Java 8 → 21, Java EE → Jakarta EE, Spring and
Spring Boot; .NET Framework and .NET Core → .NET 10 (C# and Visual Basic); PHP 5 and 7 → PHP 8, Laravel and Symfony.
The extension runs the Renova CLI on your machine (Java 21+ needed to run Renova itself, whatever the project is
written in); only AI requests leave it, on your own key. It starts in a workspace that has a `pom.xml`, a
`build.gradle`, a `.sln`, `.csproj` or `.vbproj`, or a `composer.json`. Verifying a migration needs the project's own
tools: Maven or Gradle and a JDK, the .NET SDK, or PHP and Composer.

- **Renova: Assess Workspace** (or the Renova view in the activity bar, or the status bar item): findings appear as
  diagnostics in the editor and the Problems panel, with who resolves them (an automatic recipe or rule, AI checked by
  the build, or a person). The Renova view shows the plan as steps and the places they apply to; select a place to
  open it, hover a step for its guidance.
- **Renova: Migrate…**: AI, the project's tests, behaviour verification (Docker), and the folder for the migrated copy.
  Progress shows in a notification and the Renova output channel; then open the report or the migrated copy (a git
  repository with one commit per stage). The project itself is never changed.
- **Renova: Choose AI Provider…** and **Renova: Set API Key…**: saved in the Renova user config shared with the CLI,
  desktop app and IntelliJ plugin. The key is passed to the CLI on standard input, never on a command line.

Settings: `renova.java` (Java 21+ executable; default `JAVA_HOME`, then `java` on the PATH), `renova.cliJar` (default:
the jar bundled with the extension), `renova.assessOnOpen`.

## Build

```sh
mvn package -DskipTests        # in the repository root: builds cli/target/renova.jar
cd ide/vscode
npm install
npm test                       # unit tests, plus the real CLI on a test app if it is built
npm run test:vscode            # inside a real VS Code (downloaded on first use), against renova-test-apps/claims-portal
npm run package                # renova-0.1.0.vsix with the CLI bundled
```

Install with **Extensions › … › Install from VSIX…** or `code --install-extension renova-0.1.0.vsix`.
