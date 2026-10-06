# Renova Desktop

A **JavaFX** app for assessing and migrating legacy projects on your own machine, for code that must not
leave it. The [Renova engine](../engine) runs inside the app: no server, no account. Only AI requests leave the
machine, on your own key.

| Screen | |
|---|---|
| Projects | Open a project folder, or one opened before |
| Project | The assessment: findings by category, automation rate, the playbook, and the plan with each step's guidance and files. **Findings** lists every place a rule matched, filtered by category, resolver and text (double-click opens the file). Choose a bundled playbook or your own playbook file, **Re-assess**, **Export** the assessment as Markdown or JSON, **Migrate…** |
| Migrate | AI and retrieval, running the project's tests, behaviour verification (Docker), repair rounds, and where the migrated copy goes. **Advanced:** scenario file, AI repair of behaviour differences on or off, Maven `settings.xml` and offline builds |
| Migration | Live log while it runs. Then build and test result, behaviour, AI usage and steps for a person; **Overview** with each stage's details and the build errors; **Behaviour** with both answers to every request side by side, database changes and accepted changes, and **Verify behaviour again**; **Changes** (every stage's diff); the **Report** rendered; **AI exchanges** (every request to the provider: files offered and their roles, rules or errors, the answer and tokens); the **Log** |
| Migrations | Every migration run from this app (kept in `~/.config/renova/desktop-migrations.json`), and **Open migrated folder…** for any other migrated copy. Removing one from the history leaves its copy on disk |
| Settings | AI provider, model, effort, retrieval, your API keys and a custom endpoint per provider (gateway, proxy or on-premises server), in the Renova user config shared with the CLI (`~/.config/renova/config.properties`); environment variables such as `ANTHROPIC_API_KEY` take precedence |

A migration is shown from the files in its migrated copy (`.renova/report.json`, `behaviour.json`, `ai/`,
`progress.log`), so a past migration looks the same as one that just finished.

The look is AtlantaFX's Primer theme, light or dark.

## Run

```sh
mvn -pl desktop -am install -DskipTests
java -jar desktop/target/renova-desktop-0.1.0-SNAPSHOT.jar            # or: mvn -pl desktop javafx:run
java -jar desktop/target/renova-desktop-0.1.0-SNAPSHOT.jar --open=/path/to/project
```

Options: `--open=DIR` opens a project, `--show=settings|migrations` opens Settings or the history,
`--workspace=DIR` shows a migrated copy, `--theme=light|dark` picks the theme (and
remembers it). Development aids for checking screens: `--snapshot-dir=DIR` saves a PNG of each screen shortly after it
appears (at least `--snapshot-delay=SECONDS` after), `--migrate` (with `--open`) starts a migration with the default options and no AI, and `--tab=NAME` opens that
tab of the finished migration or the project.

## Package

```sh
desktop/package.sh                 # self-contained app image in desktop/target/dist/Renova (Java runtime included)
desktop/package.sh --type deb      # or rpm on Linux, dmg/pkg on macOS, msi/exe on Windows
```

`jpackage` builds for the operating system it runs on, so build each installer on its own OS.

Requires Java 21 to build. JavaFX 21 (LTS) and AtlantaFX 2.1 come from Maven Central.
