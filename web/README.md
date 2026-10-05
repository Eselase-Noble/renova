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

Open the console and **set up** the first account and organisation. Everyone else joins by invitation.

Set `RENOVA_API_URL` for the console when the API is elsewhere (read at start, no rebuild needed). API
options: `--renova.data-dir=…`, `--server.port=…`, `--renova.project-roots=/srv/projects,/opt/apps`
(the only directories projects may be added from; default: the home directory of the account the API runs as)
and `RENOVA_MASTER_KEY` (see Security).

## Accounts and organisations

- **Setup:** the first visit creates the first account and its organisation. After that, new accounts are
  created only by accepting an invitation.
- **Organisations** keep projects, migrations and AI keys apart. Anyone can create another organisation and
  switch between theirs in the sidebar.
- **Roles** in an organisation: **Viewer** sees projects, assessments, migrations and reports; **Member** also
  starts migrations; **Admin** also adds and removes projects, sets the AI provider and keys, and manages members
  and invitations; **Owner** also manages owners. An organisation always keeps at least one owner.
- **Invitations** are links an admin shares. They work once, expire after 7 days, and only a hash of the token
  is stored. Someone who already has an account joins with their password.

## What it does

- **Projects:** register a directory on the server; Renova detects the ecosystem and playbook. The project
  is only read; migrations work on copies.
- **Assessment:** findings by category, automation rate, and the plan with who resolves each step.
- **Migrations:** background jobs (one at a time by default) with live progress. Options: AI with retrieval,
  running the project's tests, behavioural verification, AI repair rounds.
- **Review:** stages, build errors, behaviour differences (both answers side by side, database changes,
  accepted changes), every stage's diff, the log, and the Markdown report to download.
- **Settings:** per organisation: AI provider, model, effort, retrieval, and the organisation's own keys and
  optional endpoints (for example an on-premises OpenAI-compatible server). Keys are write-only and only ever
  returned masked. The server's environment variables and personal Renova config are not used, so no
  organisation can run on another's key.

## API

All endpoints except `/api/auth/*` need a signed-in session; requests that change something also need the
`X-XSRF-TOKEN` header echoing the `XSRF-TOKEN` cookie. Everything is scoped to the session's current organisation.

| Method | Path | |
|---|---|---|
| GET | `/api/auth/state` | Setup needed?, the user, their organisations and current role |
| POST | `/api/auth/setup`, `/api/auth/login`, `/api/auth/logout` | First account; sign in; sign out |
| POST | `/api/auth/organisation`, `/api/auth/password` | Switch organisation; change password |
| GET, POST | `/api/auth/invitations/{token}`, `…/accept` | Invitation details; join `{name?, password}` |
| POST | `/api/orgs` | Create an organisation (you own it) |
| GET | `/api/org` | Current organisation and members |
| PATCH, DELETE | `/api/org/members/{userId}` | Change a role; remove (or leave) |
| GET, POST, DELETE | `/api/org/invitations` | Invitations; the token is returned only on creation |
| GET, POST | `/api/projects` | List; add `{path, name?}` (admin) |
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

- Passwords are hashed with BCrypt; five failed sign-ins lock an email for five minutes; signing in starts a new
  session. The session cookie is HttpOnly and SameSite=Lax (set `server.servlet.session.cookie.secure=true`
  behind HTTPS). CSRF protection uses Spring Security's single-page-app support.
- Organisation keys are encrypted with AES-256-GCM. The master key is `RENOVA_MASTER_KEY` (base64, 32 bytes) or
  `master.key` in the data directory, created on first start and readable only by the API's account. Keep it out
  of backups of the data directory, or the backup holds both the keys and what decrypts them.
- Projects can only be added from `renova.project-roots`, checked after resolving symbolic links.
- The API listens on `127.0.0.1` by default. To serve a team, put it and the console behind HTTPS.
- Not yet: single sign-on (OIDC/SAML), audit log, licensing.
