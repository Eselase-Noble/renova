"use client";

import { useQuery } from "@tanstack/react-query";
import { ArrowRight, BookOpenCheck, Lightbulb, ListChecks, ShieldCheck } from "lucide-react";

import { Empty, ErrorState, LoadingRows, PageHeader } from "@/components/page";
import { ToneBadge } from "@/components/status";
import { api, type Playbook } from "@/lib/api";
import { plural } from "@/lib/format";

/** Target names as the playbook writes them (jakartaEE) made readable (Jakarta EE). */
const targetName = (key: string) => key.replace(/([a-z])([A-Z])/g, "$1 $2").replace(/^./, (c) => c.toUpperCase());

export default function PlaybooksPage() {
  const playbooks = useQuery({ queryKey: ["playbooks"], queryFn: api.playbooks, staleTime: 300_000 });
  const projects = useQuery({ queryKey: ["projects"], queryFn: api.projects });
  return (
    <>
      <PageHeader
        title="Playbooks"
        description="A playbook is a migration path written as rules: what to detect, and who or what resolves it. These are installed on this server."
      />
      {playbooks.error ? (
        <ErrorState error={playbooks.error} />
      ) : playbooks.isPending ? (
        <LoadingRows />
      ) : playbooks.data.length === 0 ? (
        <Empty title="No playbooks installed" icon={BookOpenCheck}>
          Playbooks come with ecosystem plugins on the server&apos;s classpath.
        </Empty>
      ) : (
        <div className="grid gap-5 xl:grid-cols-2">
          {playbooks.data.map((p) => (
            <PlaybookCard key={p.id} playbook={p} projects={(projects.data ?? []).filter((x) => x.playbook === p.id).length} />
          ))}
        </div>
      )}
    </>
  );
}

function PlaybookCard({ playbook: p, projects }: { playbook: Playbook; projects: number }) {
  const counts = [
    { icon: ListChecks, value: p.rules, label: "rules", hint: "Detect and fix" },
    { icon: ShieldCheck, value: p.guards, label: "guards", hint: "Checked on the migrated code" },
    { icon: Lightbulb, value: p.knowledgeCards, label: "knowledge notes", hint: "Given to AI when relevant" },
  ];
  return (
    <article className="flex flex-col overflow-hidden rounded-xl border bg-card shadow-(--shadow-card)">
      <div className="flex items-start gap-3.5 p-5">
        <span className="grid size-10 shrink-0 place-items-center rounded-lg bg-brand/10 text-brand">
          <BookOpenCheck className="size-5" />
        </span>
        <div className="min-w-0 flex-1">
          <h2 className="text-[15px] leading-6 font-semibold tracking-tight">{p.name}</h2>
          <div className="mt-0.5 flex flex-wrap items-center gap-1.5">
            <span className="font-mono text-xs text-muted-foreground">{p.id}</span>
            <ToneBadge tone="muted">v{p.version}</ToneBadge>
            <ToneBadge tone="muted" className="capitalize">{p.ecosystem}</ToneBadge>
          </div>
        </div>
      </div>
      {p.description && <p className="px-5 pb-4 text-sm text-muted-foreground">{p.description}</p>}
      {Object.keys(p.targets).length > 0 && (
        <dl className="mx-5 mb-5 divide-y rounded-lg border text-sm">
          {Object.entries(p.targets).map(([key, value]) => (
            <div key={key} className="flex items-baseline gap-3 px-3.5 py-2">
              <dt className="w-28 shrink-0 text-[13px] text-muted-foreground">{targetName(key)}</dt>
              <dd className="flex min-w-0 items-baseline gap-2">
                <ArrowRight className="size-3.5 shrink-0 translate-y-0.5 text-muted-foreground" />
                <span className="font-medium">{value}</span>
              </dd>
            </div>
          ))}
        </dl>
      )}
      <div className="mt-auto grid grid-cols-3 divide-x border-t bg-muted/30">
        {counts.map(({ icon: Icon, value, label, hint }) => (
          <div key={label} className="px-4 py-3" title={hint}>
            <div className="flex items-center gap-1.5 text-lg font-semibold tabular-nums">
              <Icon className="size-4 text-muted-foreground" /> {value}
            </div>
            <div className="text-xs text-muted-foreground">{label}</div>
          </div>
        ))}
      </div>
      <div className="border-t px-5 py-2.5 text-xs text-muted-foreground">
        {projects ? `Used by ${plural(projects, "project")} in this organisation` : "No project in this organisation uses it yet"}
      </div>
    </article>
  );
}
