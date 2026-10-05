# Renova Desktop

A **JavaFX** app for assessing and migrating legacy projects on your own machine, for code that must not
leave it. The [Renova engine](../engine) runs inside the app: no server, no account. Only AI requests leave the
machine, on your own key.

| Screen | |
|---|---|
| Projects | Open a project folder, or one opened before |
| Project | The assessment: findings by category, automation rate, and the plan with each step's guidance and files; **Migrate…** |
| Migrate | AI and retrieval, running the project's tests, behaviour verification (Docker), repair rounds, and where the migrated copy goes |
| Migration | Live log while it runs; then build and test result, behaviour, AI usage, stages, build errors and steps for a person, every stage's diff, and the report |
| Settings | AI provider, model, effort, retrieval and your API keys, in the Renova user config shared with the CLI (`~/.config/renova/config.properties`); environment variables such as `ANTHROPIC_API_KEY` take precedence |

The look is AtlantaFX's Primer theme, light or dark.

## Run

```sh
mvn -pl desktop -am install -DskipTests
java -jar desktop/target/renova-desktop-0.1.0-SNAPSHOT.jar            # or: mvn -pl desktop javafx:run
java -jar desktop/target/renova-desktop-0.1.0-SNAPSHOT.jar --open=/path/to/project
```

Options: `--open=DIR` opens a project, `--show=settings` opens Settings, `--theme=light|dark` picks the theme (and
remembers it). Development aids for checking screens: `--snapshot-dir=DIR` saves a PNG of each screen shortly after it
appears, `--migrate` (with `--open`) starts a migration with the default options and no AI, and `--tab=NAME` opens that
tab of the finished migration.

## Package

```sh
desktop/package.sh                 # self-contained app image in desktop/target/dist/Renova (Java runtime included)
desktop/package.sh --type deb      # or rpm on Linux, dmg/pkg on macOS, msi/exe on Windows
```

`jpackage` builds for the operating system it runs on, so build each installer on its own OS.

Requires Java 21 to build. JavaFX 21 (LTS) and AtlantaFX 2.1 come from Maven Central.
