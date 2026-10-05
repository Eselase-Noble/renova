# Renova console

The Renova web console: Next.js 16 (App Router, TypeScript), Tailwind CSS 4, shadcn/ui (Base UI), TanStack Query.
It forwards `/api/*` to the Renova web API (`src/app/api/[...path]/route.ts`), at `RENOVA_API_URL` (default
`http://127.0.0.1:8787`), read when the console starts serving, so one build works in every environment.

```sh
npm install
npm run dev     # http://localhost:3000
npm run lint
npm run build
```

| Page | |
|---|---|
| `/` | Dashboard |
| `/projects`, `/projects/[id]` | Projects, assessment, start a migration |
| `/migrations`, `/migrations/[id]` | Runs: overview, behaviour, changes (diffs), live log |
| `/settings` | AI provider and your own keys |

Next.js 16 differs from earlier versions; read the guides in `node_modules/next/dist/docs/` before changing
routing or data fetching (see `AGENTS.md`).
