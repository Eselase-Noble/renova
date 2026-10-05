# Renova Web

**Status: planned, not started.**

The web console and REST API for the [Renova engine](../engine). It can be hosted as SaaS or
installed in a customer's own network.

Planned scope:
- **Backend:** Spring Boot service embedding the engine. It provides projects (upload or Git URL),
  runs analysis and migrations as background jobs, and stores reports. It exposes a REST API, which
  the IDE plugins can also use.
- **Frontend:** React and TypeScript single-page app built with Vite, styled with Tailwind CSS and
  shadcn/ui components, with TanStack Query for server state. It provides an assessment dashboard
  (findings by category, automation rate), the plan, a per-stage diff review, and report export.
- Accounts, organisations and licensing.
