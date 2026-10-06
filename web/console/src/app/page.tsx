"use client";

import { useQuery } from "@tanstack/react-query";
import { ArrowRight, Check, CircleCheckBig, FolderGit2, Gauge as GaugeIcon, Plus, Sparkles, Workflow } from "lucide-react";
import Link from "next/link";

import { ColumnChart, type ColumnDay } from "@/components/charts";
import { MigrationTable } from "@/components/migration-table";
import { Empty, ErrorState, LoadingRows, Meter, PageHeader, Panel, Stat } from "@/components/page";
import { StatusDot, STATUS_LABEL } from "@/components/status";
import { buttonVariants } from "@/components/ui/button";
import { active, api, type Migration, type Project } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { ago, duration, percent, plural, tokens } from "@/lib/format";
import { cn } from "@/lib/utils";

const OUTCOMES = [
  { key: "PASSED", label: "Passed", color: "var(--status-good)" },
  { key: "FAILED", label: "Failed", color: "var(--status-critical)" },
  { key: "ERROR", label: "Error", color: "var(--status-serious)" },
];

/** The last `count` days up to today (UTC), each with how its migrations ended. */
function activity(migrations: Migration[], count: number): ColumnDay[] {
  const today = new Date();
  return Array.from({ length: count }, (_, i) => {
    const day = new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), today.getUTCDate() - (count - 1 - i)));
    const date = day.toISOString().slice(0, 10);
    const on = migrations.filter((m) => m.createdAt.slice(0, 10) === date);
    return { date, parts: OUTCOMES.map((o) => ({ ...o, value: on.filter((m) => m.status === o.key).length })) };
  });
}

export default function Overview() {
  const auth = useAuth();
  const projects = useQuery({ queryKey: ["projects"], queryFn: api.projects });
  const migrations = useQuery({
    queryKey: ["migrations"],
    queryFn: api.migrations,
    refetchInterval: (q) => (q.state.data?.some((m) => active(m.status)) ? 3000 : 30_000),
  });

  if (projects.error || migrations.error) return <ErrorState error={projects.error ?? migrations.error} />;

  const all = migrations.data ?? [];
  const judged = all.filter((m) => m.status === "PASSED" || m.status === "FAILED");
  const passed = all.filter((m) => m.status === "PASSED").length;
  const withSummary = all.filter((m) => m.summary);
  const automation = withSummary.length ? withSummary.reduce((n, m) => n + (m.summary?.automationRate ?? 0), 0) / withSummary.length : null;
  const tokensUsed = withSummary.reduce((n, m) => n + (m.summary?.inputTokens ?? 0) + (m.summary?.outputTokens ?? 0), 0);
  const aiRequests = withSummary.reduce((n, m) => n + (m.summary?.aiRequests ?? 0), 0);
  const running = all.filter((m) => active(m.status));
  const firstName = auth.data?.user?.name.split(/\s+/)[0];

  return (
    <>
      <PageHeader
        title={firstName ? `Welcome back, ${firstName}` : "Overview"}
        description={`Legacy projects, their assessments and migrations in ${auth.data?.organisation?.name ?? "your organisation"}.`}
        actions={
          auth.can("ADMIN") && (
            <Link href="/projects?add=1" className={buttonVariants()}>
              <Plus /> Add project
            </Link>
          )
        }
      />
      <div className="space-y-6">
        {auth.can("ADMIN") && <GettingStarted projects={projects.data} migrations={migrations.data} />}
        <div className="grid grid-cols-2 gap-4 xl:grid-cols-4">
          <Stat label="Projects" icon={FolderGit2} value={projects.data?.length ?? "—"} hint={running.length ? `${plural(running.length, "migration")} in progress` : "None being migrated now"} />
          <Stat
            label="Migrations passed"
            icon={CircleCheckBig}
            value={judged.length ? percent(passed / judged.length) : "—"}
            hint={judged.length ? `${passed} of ${judged.length} finished: build, tests and behaviour` : "No finished migration yet"}
          >
            {judged.length > 0 && <Meter value={passed / judged.length} tone="success" label="Share of finished migrations that passed" />}
          </Stat>
          <Stat
            label="Automation rate"
            icon={GaugeIcon}
            value={automation === null ? "—" : percent(automation)}
            hint={automation === null ? "Shown after the first migration" : "Average share of work needing no human decision"}
          >
            {automation !== null && <Meter value={automation} label="Average automation rate" />}
          </Stat>
          <Stat label="AI tokens used" icon={Sparkles} value={tokens(tokensUsed)} hint={aiRequests ? `${plural(aiRequests, "request")} on your own key` : "On your organisation's own key"} />
        </div>
        <div className="grid gap-6 xl:grid-cols-3">
          <Panel title="Migration activity" description="Migrations started in the last 14 days, by how they ended." className="xl:col-span-2">
            {migrations.isPending ? <LoadingRows /> : <ColumnChart days={activity(all, 14)} series={OUTCOMES} unit="migrations" />}
          </Panel>
          <Panel
            title="In progress"
            actions={running.length > 0 && <span className="text-xs text-muted-foreground">{plural(running.length, "migration")}</span>}
            bodyClassName="p-0"
          >
            {running.length === 0 ? (
              <div className="flex flex-col items-center px-5 py-10 text-center">
                <span className="mb-3 grid size-10 place-items-center rounded-full bg-muted text-muted-foreground">
                  <Workflow className="size-5" />
                </span>
                <div className="text-sm font-medium">Nothing is running</div>
                <p className="mt-1 text-[13px] text-muted-foreground">Start a migration from a project. It runs in the background and reports here.</p>
              </div>
            ) : (
              <ul className="divide-y">
                {running.map((m) => (
                  <li key={m.id}>
                    <Link href={`/migrations/${m.id}`} className="flex items-center gap-3 px-5 py-3 outline-none hover:bg-muted/50 focus-visible:bg-muted/50">
                      <StatusDot status={m.status} />
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-sm font-medium">{m.projectName}</span>
                        <span className="block truncate text-xs text-muted-foreground">
                          {STATUS_LABEL[m.status]}
                          {m.startedAt ? ` · ${duration(m.startedAt, null)}` : ` · queued ${ago(m.createdAt)}`}
                        </span>
                      </span>
                      <ArrowRight className="size-4 text-muted-foreground" />
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </Panel>
        </div>
        <div className="grid gap-6 xl:grid-cols-3">
          <Panel
            title="Recent migrations"
            className="xl:col-span-2"
            bodyClassName="p-0"
            actions={
              <Link href="/migrations" className={buttonVariants({ variant: "ghost", size: "sm" })}>
                All migrations <ArrowRight />
              </Link>
            }
          >
            {migrations.isPending ? (
              <div className="p-5">
                <LoadingRows />
              </div>
            ) : all.length === 0 ? (
              <div className="p-5">
                <Empty title="No migrations yet" icon={Workflow}>
                  Add a project, review its assessment, then start a migration.
                </Empty>
              </div>
            ) : (
              <MigrationTable migrations={all.slice(0, 6)} />
            )}
          </Panel>
          <Panel
            title="Projects"
            bodyClassName="p-0"
            actions={
              <Link href="/projects" className={buttonVariants({ variant: "ghost", size: "sm" })}>
                All projects <ArrowRight />
              </Link>
            }
          >
            <ProjectHealth projects={projects.data} migrations={all} />
          </Panel>
        </div>
      </div>
    </>
  );
}

/** Each project with the state of its latest migration. */
function ProjectHealth({ projects, migrations }: { projects: Project[] | undefined; migrations: Migration[] }) {
  if (!projects) {
    return (
      <div className="p-5">
        <LoadingRows />
      </div>
    );
  }
  if (projects.length === 0) {
    return <p className="px-5 py-8 text-center text-[13px] text-muted-foreground">No project has been added yet.</p>;
  }
  return (
    <ul className="divide-y">
      {projects.slice(0, 6).map((p) => {
        const latest = migrations.find((m) => m.projectId === p.id);
        return (
          <li key={p.id}>
            <Link href={`/projects/${p.id}`} className="flex items-center gap-3 px-5 py-3 outline-none hover:bg-muted/50 focus-visible:bg-muted/50">
              <StatusDot status={latest?.status ?? null} />
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm font-medium">{p.name}</span>
                <span className="block truncate text-xs text-muted-foreground">
                  {latest ? `${STATUS_LABEL[latest.status]} ${ago(latest.createdAt)}` : "Not migrated yet"}
                </span>
              </span>
              {latest?.summary && <span className="text-xs text-muted-foreground tabular-nums">{percent(latest.summary.automationRate)} automated</span>}
            </Link>
          </li>
        );
      })}
    </ul>
  );
}

/** The steps from an empty organisation to a first verified migration; hidden once all are done. */
function GettingStarted({ projects, migrations }: { projects: Project[] | undefined; migrations: Migration[] | undefined }) {
  const settings = useQuery({ queryKey: ["settings"], queryFn: api.settings });
  const org = useQuery({ queryKey: ["organisation"], queryFn: api.organisation });
  if (!projects || !migrations || !settings.data || !org.data) return null;
  const s = settings.data;
  const steps = [
    {
      title: "Connect an AI provider",
      text: "Add your organisation's own Anthropic or OpenAI key. Without one, AI steps are listed for a person.",
      done: s.provider !== "none" && !!s.providers.find((p) => p.name === s.provider)?.keyConfigured,
      href: "/settings",
      action: "Open settings",
    },
    { title: "Add a project", text: "Point Renova at a project folder on this server. It is only read.", done: projects.length > 0, href: "/projects?add=1", action: "Add project" },
    {
      title: "Run a migration",
      text: "Review the assessment, then migrate a copy and follow every stage.",
      done: migrations.length > 0,
      href: projects[0] ? `/projects/${projects[0].id}` : "/projects",
      action: "Choose a project",
    },
    { title: "Invite your team", text: "Give colleagues a role: viewer, member or admin.", done: org.data.members.length > 1, href: "/organisation", action: "Invite people" },
  ];
  const done = steps.filter((x) => x.done).length;
  if (done === steps.length) return null;
  const next = steps.findIndex((x) => !x.done);
  return (
    <Panel
      title="Get started"
      description={`${done} of ${steps.length} steps done`}
      actions={
        <div className="w-32">
          <Meter value={done / steps.length} label="Setup progress" />
        </div>
      }
      bodyClassName="p-0"
    >
      <ol className="grid divide-y md:grid-cols-2 md:divide-x md:divide-y-0 xl:grid-cols-4">
        {steps.map((step, i) => (
          <li key={step.title} className={cn("flex flex-col gap-2 p-5", i >= 2 && "md:border-t xl:border-t-0")}>
            <div className="flex items-center gap-2.5">
              <span
                className={cn(
                  "grid size-6 shrink-0 place-items-center rounded-full border text-xs font-semibold",
                  step.done ? "border-success bg-success text-background" : i === next ? "border-brand text-brand" : "text-muted-foreground",
                )}
              >
                {step.done ? <Check className="size-3.5" strokeWidth={3} /> : i + 1}
              </span>
              <span className={cn("text-sm font-medium", step.done && "text-muted-foreground line-through decoration-muted-foreground/50")}>{step.title}</span>
            </div>
            <p className="flex-1 text-[13px] text-muted-foreground">{step.text}</p>
            {!step.done && (
              <Link href={step.href} className={cn(buttonVariants({ variant: i === next ? "default" : "outline", size: "sm" }), "w-fit")}>
                {step.action} <ArrowRight />
              </Link>
            )}
          </li>
        ))}
      </ol>
    </Panel>
  );
}
