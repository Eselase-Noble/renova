# Renova Web

The web console and REST API for the [Renova engine](../engine), for a team's own server.

| Part | Path | Stack |
|---|---|---|
| REST API | [`api`](api) | Spring Boot 4, embedding the engine |
| Console | [`console`](console) | Next.js 16 (App Router, TypeScript), Tailwind CSS, shadcn/ui, TanStack Query |

## Run it

```sh
# API on http://127.0.0.1:8787 (data in ~/.renova/server)
mvn -pl web/api -am install -DskipTests
java -jar web/api/target/renova-web-api-0.1.0-SNAPSHOT.jar

# Console on http://localhost:3000, proxying /api to the API
cd web/console
npm install
npm run dev            # or: npm run build && npm start
```

Set `RENOVA_API_URL` for the console when the API is elsewhere, and `--renova.data-dir=…`,
`--server.port=…` or `--renova.user-config=…` for the API.

## What it does

- **Projects:** register a directory on the server; Renova detects the ecosystem and playbook. The project
  is only read; migrations work on copies.
- **Assessment:** findings by category, automation rate, and the plan with who resolves each step.
- **Migrations:** background jobs (one at a time by default) with live progress. Options: AI with retrieval,
  running the project's tests, behavioural verification, AI repair rounds.
- **Review:** stages, build errors, behaviour differences (both answers side by side, database changes,
  accepted changes), every stage's diff, the log, and the Markdown report to download.
- **Settings:** AI provider, model, effort and retrieval, with the customer's own keys. Keys are write-only;
  the API only returns them masked. They are stored in the Renova user config of the account the API runs as,
  the same file the CLI uses.

## API

| Method | Path | |
|---|---|---|
| GET, POST | `/api/projects` | List; add `{path, name?}` |
| GET, DELETE | `/api/projects/{id}` | |
| GET | `/api/projects/{id}/assessment` | Findings and plan (report JSON without a migration) |
| GET, POST | `/api/projects/{id}/migrations` | List; start `{ai, rag, verifyBehaviour, skipTests, maxAiIterations, playbook}` |
| GET | `/api/migrations`, `/api/migrations/{id}?since=N` | Records; progress lines from N on |
| GET | `/api/migrations/{id}/report`, `/report.md`, `/behaviour` | Reports from the workspace |
| GET | `/api/migrations/{id}/commits`, `/commits/{hash}/diff` | Stages as commits, and their diffs |
| GET | `/api/playbooks` | Installed playbooks |
| GET, PUT | `/api/settings` | AI settings (keys masked) |
| PUT, DELETE | `/api/settings/keys/{provider}` | Set or remove a key |
| POST | `/api/settings/check` | Check the key and model without generating anything |

## Security

There is no authentication yet: the API listens on `127.0.0.1` only. Put it behind an authenticating reverse
proxy before exposing it. Accounts, organisations and licensing are planned.
