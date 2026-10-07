# Renova for IntelliJ IDEA, PhpStorm and Rider

Assess and migrate legacy Java, .NET and PHP projects from IntelliJ IDEA, PhpStorm, Rider or another IDE of the
IntelliJ platform, 2025.2 or later. The plugin needs nothing of the Java plugin, so it installs in the IDEs that do
not have it. The Renova engine runs inside the IDE; only AI requests leave the machine, on your own key. Verifying
a migration needs the project's own tools on the machine: Maven or Gradle and a JDK, the .NET SDK, or PHP and Composer.

- **Tools › Renova › Assess with Renova** (or the Renova tool window): findings by category, the automation rate,
  and the plan as a tree of steps and the places they apply to. Double-click a place to open it.
- **Findings in the editor:** each finding is highlighted at its line, with what will be done about it (an automatic
  recipe or rule, AI checked by the build, or a person), and listed in the Problems view (inspection
  *Renova migration finding*).
- **Tools › Renova › Migrate with Renova…:** AI and retrieval, the project's tests, behaviour verification, repair
  rounds, and where the migrated copy goes. It runs as a background task with progress; a notification then offers
  the report and the migrated copy. The copy is a git repository with one commit per stage, so IntelliJ's Git log
  shows every stage's diff. The project itself is never changed.
- **Settings › Tools › Renova:** AI provider, model, effort, retrieval and your keys, in the Renova user config shared
  with the CLI and the desktop app. Environment variables such as `ANTHROPIC_API_KEY` take precedence.

## Build

```sh
mvn install -DskipTests                       # the Renova engine, into the local Maven repository
cd ide/intellij
./gradlew buildPlugin                         # build/distributions/renova-intellij-0.1.0.zip
./gradlew test                                # tests inside a headless IDE
```

The build downloads IntelliJ IDEA Community 2025.2 to compile against. To use an installed IntelliJ IDEA
instead, pass `-PplatformPath=/path/to/idea` (or set it in `~/.gradle/gradle.properties`).

Install the zip with **Settings › Plugins › ⚙ › Install Plugin from Disk…**. `./gradlew runIde` starts a sandbox IDE
with the plugin.
