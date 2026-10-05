"use client";

import { useQuery } from "@tanstack/react-query";
import { CheckCircle2, ChevronRight, CircleMinus, Download, Loader2, TriangleAlert, XCircle } from "lucide-react";
import Link from "next/link";
import { use, useEffect, useRef, useState } from "react";

import { BehaviourView } from "@/components/behaviour-view";
import { DiffView } from "@/components/diff-view";
import { Empty, ErrorState, LoadingRows, PageHeader, Stat } from "@/components/page";
import { BehaviourBadge, BuildBadge, MigrationStatusBadge } from "@/components/status";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { api, finished, type Commit, type MigrationReport, type StageResult } from "@/lib/api";
import { ago, duration, percent, tokens } from "@/lib/format";
import { cn } from "@/lib/utils";

export default function MigrationPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const detail = useQuery({
    queryKey: ["migration", id],
    queryFn: () => api.migration(id),
    refetchInterval: (q) => (q.state.data && finished(q.state.data.migration.status) ? false : 2000),
  });
  const done = !!detail.data && finished(detail.data.migration.status);
  const hasWorkspace = !!detail.data && detail.data.migration.status !== "QUEUED";
  const report = useQuery({ queryKey: ["report", id], queryFn: () => api.report(id), enabled: done && !!detail.data?.migration.summary });
  const behaviour = useQuery({
    queryKey: ["behaviour", id],
    queryFn: () => api.behaviour(id),
    enabled: done && !!detail.data?.migration.options.verifyBehaviour && !!detail.data?.migration.summary?.behaviour,
  });
  const commits = useQuery({
    queryKey: ["commits", id, done],
    queryFn: () => api.commits(id),
    enabled: hasWorkspace,
    refetchInterval: done ? false : 5000,
    retry: false,
  });

  if (detail.error) return <ErrorState error={detail.error} />;
  if (!detail.data) return <LoadingRows rows={6} />;
  const m = detail.data.migration;
  const s = m.summary;

  return (
    <>
      <PageHeader
        eyebrow={
          <Link href={`/projects/${m.projectId}`} className="hover:underline">
            {m.projectName}
          </Link>
        }
        title={
          <span className="flex items-center gap-3">
            Migration <span className="font-mono text-base text-muted-foreground">{m.id}</span>
          </span>
        }
        description={`${m.playbook} · started ${ago(m.createdAt)} · ${duration(m.startedAt, m.finishedAt)}`}
        actions={
          <>
            <MigrationStatusBadge status={m.status} />
            {s && (
              <a href={`/api/migrations/${id}/report.md`} download={`renova-${id}.md`} className={buttonVariants({ variant: "outline", size: "sm" })}>
                <Download /> Report
              </a>
            )}
          </>
        }
      />
      {m.error && (
        <Alert variant="destructive" className="mb-6">
          <TriangleAlert />
          <AlertTitle>The migration stopped</AlertTitle>
          <AlertDescription>{m.error}</AlertDescription>
        </Alert>
      )}
      {s && (
        <div className="mb-6 grid grid-cols-2 gap-3 lg:grid-cols-4">
          <Stat label="Build and tests" value={<BuildBadge build={s.build} />} hint={s.buildErrors ? `${s.buildErrors} error(s)` : undefined} />
          <Stat
            label="Behaviour"
            value={m.options.verifyBehaviour ? <BehaviourBadge status={s.behaviour} /> : <span className="text-base text-muted-foreground">Not checked</span>}
            hint={s.behaviourSummary ?? undefined}
          />
          <Stat
            label="AI"
            value={m.options.ai ? `${s.aiRequests} requests` : "Off"}
            hint={m.options.ai ? `${tokens(s.inputTokens)} in · ${tokens(s.outputTokens)} out · ${s.repairRounds} repair round(s)` : undefined}
          />
          <Stat label="Automated" value={percent(s.automationRate)} hint={`${s.findings} findings · ${s.manualSteps} for a person`} />
        </div>
      )}
      <Tabs defaultValue={done ? "overview" : "log"}>
        <TabsList>
          <TabsTrigger value="overview">Overview</TabsTrigger>
          {m.options.verifyBehaviour && <TabsTrigger value="behaviour">Behaviour</TabsTrigger>}
          <TabsTrigger value="changes">Changes</TabsTrigger>
          <TabsTrigger value="log">Log</TabsTrigger>
        </TabsList>
        <TabsContent value="overview" className="pt-4">
          {!done ? (
            <Empty title="Migration in progress">The overview appears when it finishes. Follow it in the Log tab.</Empty>
          ) : report.data ? (
            <Overview report={report.data} />
          ) : report.isPending && s ? (
            <LoadingRows rows={5} />
          ) : (
            <Empty title="No report">The migration stopped before a report was written.</Empty>
          )}
        </TabsContent>
        {m.options.verifyBehaviour && (
          <TabsContent value="behaviour" className="pt-4">
            {behaviour.data ? (
              <BehaviourView report={behaviour.data} />
            ) : (
              <Empty title={done ? "Behaviour was not compared" : "Not compared yet"}>
                {done ? "It runs after a passing build." : "It runs after the build passes."}
              </Empty>
            )}
          </TabsContent>
        )}
        <TabsContent value="changes" className="pt-4">
          {commits.data ? <Changes id={id} commits={commits.data} /> : <Empty title="No changes yet" />}
        </TabsContent>
        <TabsContent value="log" className="pt-4">
          <Log lines={detail.data.progress} running={!done} />
        </TabsContent>
      </Tabs>
    </>
  );
}

const STAGE_ICON: Record<StageResult["status"], React.ReactNode> = {
  APPLIED: <CheckCircle2 className="size-4 text-emerald-600 dark:text-emerald-400" />,
  PARTIAL: <TriangleAlert className="size-4 text-amber-600 dark:text-amber-400" />,
  FAILED: <XCircle className="size-4 text-red-600 dark:text-red-400" />,
  SKIPPED: <CircleMinus className="size-4 text-muted-foreground" />,
};

function Overview({ report }: { report: MigrationReport }) {
  const [open, setOpen] = useState<number | null>(null);
  const { stages, verification, manualSteps } = report.migration;
  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <CardTitle>Stages</CardTitle>
          <CardDescription>Each stage is one commit in the workspace; see Changes.</CardDescription>
        </CardHeader>
        <CardContent>
          <ol className="space-y-1">
            {stages.map((stage, i) => (
              <li key={i}>
                <button
                  type="button"
                  className="flex w-full items-start gap-3 rounded-md px-2 py-1.5 text-left text-sm hover:bg-muted/60"
                  onClick={() => setOpen(open === i ? null : i)}
                  disabled={stage.details.length === 0}
                >
                  <span className="mt-0.5">{STAGE_ICON[stage.status]}</span>
                  <span className="w-44 shrink-0 font-medium">{stage.stage}</span>
                  <span className="min-w-0 flex-1 text-muted-foreground">{stage.summary}</span>
                  {stage.details.length > 0 && (
                    <ChevronRight className={cn("mt-0.5 size-4 shrink-0 transition-transform", open === i && "rotate-90")} />
                  )}
                </button>
                {open === i && (
                  <pre className="mx-2 mb-2 max-h-80 overflow-auto rounded-md bg-muted/50 p-3 font-mono text-xs whitespace-pre-wrap">
                    {stage.details.join("\n")}
                  </pre>
                )}
              </li>
            ))}
          </ol>
        </CardContent>
      </Card>
      {verification && verification.errors.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle>Build errors</CardTitle>
          </CardHeader>
          <CardContent>
            <ul className="space-y-2 text-sm">
              {verification.errors.slice(0, 50).map((e, i) => (
                <li key={i}>
                  <span className="font-mono text-xs">{e.file ?? "(build)"}{e.line > 0 ? `:${e.line}` : ""}</span>
                  <div className="text-muted-foreground">{e.message}</div>
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>
      )}
      {manualSteps.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle>For a person</CardTitle>
            <CardDescription>Decisions Renova leaves to your team, with guidance.</CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            {manualSteps.map((step) => (
              <div key={step.order} className="space-y-1">
                <div className="text-sm font-medium">{step.rule.title}</div>
                {step.rule.fix.hint && <p className="text-sm text-muted-foreground">{step.rule.fix.hint}</p>}
                <p className="font-mono text-xs text-muted-foreground">{step.files.slice(0, 5).join(", ")}</p>
              </div>
            ))}
          </CardContent>
        </Card>
      )}
    </div>
  );
}

function Changes({ id, commits }: { id: string; commits: Commit[] }) {
  const stages = commits.slice(1); // the first commit is the unmodified copy
  const [selected, setSelected] = useState<string | null>(null);
  const hash = selected ?? stages[0]?.hash ?? null;
  const diff = useQuery({ queryKey: ["diff", id, hash], queryFn: () => api.diff(id, hash!), enabled: !!hash, staleTime: Infinity });
  if (stages.length === 0) return <Empty title="No stage has changed anything yet" />;
  return (
    <div className="grid gap-4 lg:grid-cols-[18rem_1fr]">
      <ol className="space-y-1">
        {stages.map((c) => (
          <li key={c.hash}>
            <button
              type="button"
              onClick={() => setSelected(c.hash)}
              className={cn(
                "w-full rounded-md px-3 py-2 text-left text-sm hover:bg-muted",
                c.hash === hash && "bg-muted",
              )}
            >
              <div className="line-clamp-2">{c.message.replace(/^renova: /, "")}</div>
              <div className="mt-0.5 text-xs text-muted-foreground">
                {c.filesChanged} file(s) · <span className="text-emerald-600 dark:text-emerald-400">+{c.insertions}</span>{" "}
                <span className="text-red-600 dark:text-red-400">−{c.deletions}</span>
              </div>
            </button>
          </li>
        ))}
      </ol>
      <div className="min-w-0">{diff.data !== undefined ? <DiffView diff={diff.data} /> : <LoadingRows rows={4} />}</div>
    </div>
  );
}

function Log({ lines, running }: { lines: string[]; running: boolean }) {
  const end = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (running) end.current?.scrollIntoView({ block: "nearest" });
  }, [lines.length, running]);
  return (
    <Card>
      <CardContent>
        <pre className="max-h-[32rem] overflow-auto font-mono text-xs leading-6 whitespace-pre-wrap">
          {lines.join("\n")}
          {running && (
            <span className="mt-1 flex items-center gap-2 text-muted-foreground">
              <Loader2 className="size-3 animate-spin" /> working…
            </span>
          )}
          <div ref={end} />
        </pre>
      </CardContent>
    </Card>
  );
}
