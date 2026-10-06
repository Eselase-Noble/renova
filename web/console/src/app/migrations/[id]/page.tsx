"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Ban,
  BookOpenCheck,
  CheckCircle2,
  ChevronRight,
  CircleMinus,
  Clock,
  Download,
  GitCommitVertical,
  Hammer,
  Repeat,
  RotateCw,
  Sparkles,
  TriangleAlert,
  User,
  Workflow,
  XCircle,
} from "lucide-react";
import Link from "next/link";
import { use, useState } from "react";
import { toast } from "sonner";

import { BehaviourView } from "@/components/behaviour-view";
import { DiffView } from "@/components/diff-view";
import { LogView } from "@/components/log-view";
import { CopyButton, Empty, ErrorState, Field, LoadingRows, PageHeader, Panel, Stat } from "@/components/page";
import { phases, Pipeline } from "@/components/pipeline";
import { StartMigrationDialog } from "@/components/start-migration-dialog";
import { BehaviourBadge, BuildBadge, MigrationStatusBadge, ToneBadge } from "@/components/status";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button, buttonVariants } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { api, finished, type Commit, type Migration, type MigrationReport, type StageResult } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { ago, dateTime, duration, percent, plural, tokens } from "@/lib/format";
import { cn } from "@/lib/utils";

export default function MigrationPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const auth = useAuth();
  const queryClient = useQueryClient();
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
  const org = useQuery({ queryKey: ["organisation"], queryFn: api.organisation, staleTime: 60_000 });
  const [cancelling, setCancelling] = useState(false);
  const [rerunning, setRerunning] = useState(false);
  const cancel = useMutation({
    mutationFn: () => api.cancelMigration(id),
    onSuccess: () => {
      setCancelling(false);
      queryClient.invalidateQueries({ queryKey: ["migration", id] });
      queryClient.invalidateQueries({ queryKey: ["migrations"] });
      toast.success("Cancelling the migration");
    },
    onError: (e) => toast.error(e.message),
  });

  if (detail.error) return <ErrorState error={detail.error} />;
  if (!detail.data) return <LoadingRows rows={6} />;
  const m = detail.data.migration;
  const s = m.summary;
  const startedBy = org.data?.members.find((member) => member.id === m.startedBy)?.name;
  const steps = phases(m, detail.data.progress);
  // A failing build is expected when the plan had steps for AI and this migration ran without it.
  const skippedAi = s?.build === "FAILS" && s.aiRequests === 0 ? (report.data?.plan ?? []).filter((p) => p.strategy === "ai").map((p) => p.title) : [];
  const current = steps.find((p) => p.state === "current");

  return (
    <>
      <PageHeader
        icon={Workflow}
        title={
          <span className="flex flex-wrap items-center gap-x-3 gap-y-1">
            <span className="truncate">{m.projectName}</span>
            <MigrationStatusBadge status={m.status} />
          </span>
        }
        description={
          <span className="flex items-center gap-1">
            <span className="font-mono text-xs">{m.id}</span>
            <CopyButton value={m.id} label="Copy the migration id" />
          </span>
        }
        meta={
          <>
            <Link href="/playbooks" className="inline-flex items-center gap-1.5 font-mono hover:text-foreground hover:underline">
              <BookOpenCheck className="size-3.5" /> {m.playbook}
            </Link>
            {startedBy && (
              <span className="inline-flex items-center gap-1.5">
                <User className="size-3.5" /> {startedBy}
              </span>
            )}
            <span className="inline-flex items-center gap-1.5" title={dateTime(m.createdAt)}>
              <Clock className="size-3.5" /> Started {ago(m.createdAt)}
            </span>
            <span className="inline-flex items-center gap-1.5 tabular-nums">
              <RotateCw className="size-3.5" /> {m.startedAt ? duration(m.startedAt, m.finishedAt) : "Waiting for a free slot"}
            </span>
          </>
        }
        actions={
          <>
            {!done && auth.can("MEMBER") && (
              <Button variant="outline" onClick={() => setCancelling(true)}>
                <Ban /> Cancel
              </Button>
            )}
            {done && auth.can("MEMBER") && (
              <Button variant="outline" onClick={() => setRerunning(true)}>
                <Repeat /> Run again
              </Button>
            )}
            {s && (
              <a href={`/api/migrations/${id}/report.md`} download={`renova-${id}.md`} className={buttonVariants({ variant: "outline" })}>
                <Download /> Report
              </a>
            )}
          </>
        }
      />
      <div className="space-y-6">
        {m.error && (
          <Alert variant="destructive">
            <TriangleAlert />
            <AlertTitle>The migration stopped</AlertTitle>
            <AlertDescription>{m.error}</AlertDescription>
          </Alert>
        )}
        {skippedAi.length > 0 && (
          <Alert>
            <Sparkles />
            <AlertTitle>The build fails because the AI steps were not done</AlertTitle>
            <AlertDescription>
              <p>
                This migration ran without AI, so {plural(skippedAi.length, "step")} of the plan {skippedAi.length === 1 ? "was" : "were"} left undone and
                the code that depends on {skippedAi.length === 1 ? "it" : "them"} does not build yet:
              </p>
              <ul className="my-1.5 list-disc pl-5">
                {skippedAi.map((title) => (
                  <li key={title}>{title}</li>
                ))}
              </ul>
              <p>
                <Link href="/settings" className="font-medium underline">Add an AI provider key in Settings</Link> and run it again: Renova then makes
                these changes and repairs the build errors that are left. Or make the changes by hand in the workspace.
              </p>
            </AlertDescription>
          </Alert>
        )}
        {m.status === "CANCELLED" && (
          <Alert>
            <Ban />
            <AlertTitle>This migration was cancelled</AlertTitle>
            <AlertDescription>The stages committed before it stopped are kept in the workspace; see Changes.</AlertDescription>
          </Alert>
        )}
        <Panel
          title="Pipeline"
          description={
            current?.note ?? (m.status === "QUEUED" ? "Waiting for a migration that is running to finish." : done ? "Each stage is one commit in the workspace." : undefined)
          }
        >
          <Pipeline phases={steps} />
        </Panel>
        {s && (
          <div className="grid grid-cols-2 gap-4 xl:grid-cols-4">
            <Stat label="Build and tests" icon={Hammer} value={<BuildBadge build={s.build} />} hint={s.buildErrors ? plural(s.buildErrors, "error") : m.options.skipTests ? "Tests were skipped" : "Compiled and tested"} />
            <Stat
              label="Behaviour"
              icon={GitCommitVertical}
              value={m.options.verifyBehaviour ? <BehaviourBadge status={s.behaviour} /> : <ToneBadge tone="muted">Not checked</ToneBadge>}
              hint={s.behaviourSummary ?? (m.options.verifyBehaviour ? undefined : "Behaviour verification was off")}
            />
            <Stat
              label="AI"
              icon={Sparkles}
              value={m.options.ai ? plural(s.aiRequests, "request") : "Off"}
              hint={m.options.ai ? `${tokens(s.inputTokens)} in · ${tokens(s.outputTokens)} out · ${plural(s.repairRounds, "repair round")}` : "AI steps are listed for a person"}
            />
            <Stat label="Automated" value={percent(s.automationRate)} hint={`${plural(s.findings, "finding")} · ${s.manualSteps} for a person`} />
          </div>
        )}
        <Tabs defaultValue={done ? "overview" : "log"}>
          <TabsList variant="line" className="w-full justify-start border-b">
            <TabsTrigger value="overview" className="flex-none px-3">Overview</TabsTrigger>
            {m.options.verifyBehaviour && <TabsTrigger value="behaviour" className="flex-none px-3">Behaviour</TabsTrigger>}
            <TabsTrigger value="changes" className="flex-none px-3">Changes</TabsTrigger>
            <TabsTrigger value="log" className="flex-none px-3">Log</TabsTrigger>
            <TabsTrigger value="details" className="flex-none px-3">Details</TabsTrigger>
          </TabsList>
          <TabsContent value="overview" className="pt-5">
            {!done ? (
              <Empty title="Migration in progress" icon={Workflow}>
                The overview appears when it finishes. Follow it in the Log tab.
              </Empty>
            ) : report.data ? (
              <Overview report={report.data} />
            ) : report.isPending && s ? (
              <LoadingRows rows={5} />
            ) : (
              <Empty title="No report">The migration stopped before a report was written.</Empty>
            )}
          </TabsContent>
          {m.options.verifyBehaviour && (
            <TabsContent value="behaviour" className="pt-5">
              {behaviour.data ? (
                <BehaviourView report={behaviour.data} />
              ) : (
                <Empty title={done ? "Behaviour was not compared" : "Not compared yet"}>
                  {done ? "It runs after a passing build." : "It runs after the build passes."}
                </Empty>
              )}
            </TabsContent>
          )}
          <TabsContent value="changes" className="pt-5">
            {commits.data ? <Changes id={id} commits={commits.data} /> : <Empty title="No changes yet" icon={GitCommitVertical} />}
          </TabsContent>
          <TabsContent value="log" className="pt-5">
            <LogView lines={detail.data.progress} running={!done} name={`renova-${id}`} />
          </TabsContent>
          <TabsContent value="details" className="pt-5">
            <Details migration={m} startedBy={startedBy} />
          </TabsContent>
        </Tabs>
      </div>
      <Dialog open={cancelling} onOpenChange={setCancelling}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Cancel this migration?</DialogTitle>
            <DialogDescription>
              Renova stops at the current step. Stages already committed stay in the workspace; the project itself is untouched.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setCancelling(false)}>
              Keep running
            </Button>
            <Button variant="destructive" onClick={() => cancel.mutate()} disabled={cancel.isPending}>
              Cancel migration
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      {rerunning && <StartMigrationDialog project={{ id: m.projectId, name: m.projectName }} initial={m.options} open onOpenChange={setRerunning} />}
    </>
  );
}

const STAGE_ICON: Record<StageResult["status"], React.ReactNode> = {
  APPLIED: <CheckCircle2 className="size-4 text-success" />,
  PARTIAL: <TriangleAlert className="size-4 text-warning" />,
  FAILED: <XCircle className="size-4 text-danger" />,
  SKIPPED: <CircleMinus className="size-4 text-muted-foreground" />,
};

const STAGE_STATUS: Record<StageResult["status"], string> = { APPLIED: "Applied", PARTIAL: "Partly applied", FAILED: "Failed", SKIPPED: "Skipped" };

function Overview({ report }: { report: MigrationReport }) {
  const [open, setOpen] = useState<number | null>(null);
  const { stages, verification, manualSteps } = report.migration;
  return (
    <div className="space-y-6">
      <Panel title="Stages" description="Each stage is one commit in the workspace; see Changes for its diff." bodyClassName="p-0">
        <ol className="divide-y">
          {stages.map((stage, i) => (
            <li key={i}>
              <button
                type="button"
                className="flex w-full items-center gap-3 px-5 py-3 text-left text-sm outline-none enabled:hover:bg-muted/50 enabled:focus-visible:bg-muted/50"
                aria-expanded={open === i}
                onClick={() => setOpen(open === i ? null : i)}
                disabled={stage.details.length === 0}
              >
                <span title={STAGE_STATUS[stage.status]}>{STAGE_ICON[stage.status]}</span>
                <span className="w-44 shrink-0 font-mono text-[13px] font-medium">{stage.stage}</span>
                <span className="min-w-0 flex-1 text-muted-foreground">{stage.summary}</span>
                <span className="hidden text-xs text-muted-foreground sm:inline">{STAGE_STATUS[stage.status]}</span>
                <ChevronRight className={cn("size-4 shrink-0 text-muted-foreground transition-transform", open === i && "rotate-90", stage.details.length === 0 && "invisible")} />
              </button>
              {open === i && (
                <pre className="scroll-thin mx-5 mb-4 max-h-80 overflow-auto rounded-lg border bg-muted/40 p-3 font-mono text-xs leading-5 whitespace-pre-wrap">
                  {stage.details.join("\n")}
                </pre>
              )}
            </li>
          ))}
        </ol>
      </Panel>
      {verification && verification.errors.length > 0 && (
        <Panel title="Build errors" description={`${plural(verification.errors.length, "error")} from the compiler, the build file or the tests.`} bodyClassName="p-0">
          <ul className="divide-y">
            {verification.errors.slice(0, 50).map((e, i) => (
              <li key={i} className="flex gap-3 px-5 py-3 text-sm">
                <XCircle className="mt-0.5 size-4 shrink-0 text-danger" />
                <div className="min-w-0">
                  <div className="font-mono text-xs break-all">
                    {e.file ?? "(build)"}
                    {e.line > 0 && <span className="text-muted-foreground">:{e.line}</span>}
                  </div>
                  <div className="mt-0.5 break-words text-muted-foreground">{e.message}</div>
                </div>
              </li>
            ))}
          </ul>
          {verification.errors.length > 50 && <p className="border-t px-5 py-3 text-[13px] text-muted-foreground">The first 50 are shown; the report lists them all.</p>}
        </Panel>
      )}
      {manualSteps.length > 0 && (
        <Panel title="For a person" description="Decisions Renova leaves to your team, with guidance." bodyClassName="p-0">
          <ul className="divide-y">
            {manualSteps.map((step) => (
              <li key={step.order} className="flex gap-3 px-5 py-4">
                <User className="mt-0.5 size-4 shrink-0 text-warning" />
                <div className="min-w-0 space-y-1.5">
                  <div className="text-sm font-medium">{step.rule.title}</div>
                  {step.rule.fix.hint && <p className="max-w-3xl text-sm text-muted-foreground">{step.rule.fix.hint}</p>}
                  <p className="font-mono text-xs break-all text-muted-foreground">
                    {step.files.slice(0, 5).join(", ")}
                    {step.files.length > 5 && ` and ${step.files.length - 5} more`}
                  </p>
                </div>
              </li>
            ))}
          </ul>
        </Panel>
      )}
    </div>
  );
}

function Changes({ id, commits }: { id: string; commits: Commit[] }) {
  const stages = commits.slice(1); // the first commit is the unmodified copy
  const [selected, setSelected] = useState<string | null>(null);
  const hash = selected ?? stages[0]?.hash ?? null;
  const diff = useQuery({ queryKey: ["diff", id, hash], queryFn: () => api.diff(id, hash!), enabled: !!hash, staleTime: Infinity });
  if (stages.length === 0) return <Empty title="No stage has changed anything yet" icon={GitCommitVertical} />;
  return (
    <div className="grid items-start gap-5 lg:grid-cols-[19rem_minmax(0,1fr)]">
      <Panel title="Stages" description={`${plural(stages.length, "commit")} after the unmodified copy`} bodyClassName="p-1.5" className="lg:sticky lg:top-20">
        <ol className="scroll-thin max-h-[30rem] space-y-0.5 overflow-y-auto">
          {stages.map((c, i) => (
            <li key={c.hash}>
              <button
                type="button"
                onClick={() => setSelected(c.hash)}
                aria-current={c.hash === hash}
                className={cn(
                  "flex w-full gap-2.5 rounded-lg px-2.5 py-2 text-left text-[13px] outline-none hover:bg-muted focus-visible:bg-muted",
                  c.hash === hash && "bg-brand/10 hover:bg-brand/10",
                )}
              >
                <span className={cn("mt-px grid size-5 shrink-0 place-items-center rounded-full border text-[10px] font-semibold tabular-nums", c.hash === hash ? "border-brand text-brand" : "text-muted-foreground")}>
                  {i + 1}
                </span>
                <span className="min-w-0">
                  <span className="line-clamp-2 font-medium">{c.message.replace(/^renova: /, "")}</span>
                  <span className="mt-0.5 block text-xs text-muted-foreground tabular-nums">
                    <span className="font-mono">{c.hash.slice(0, 7)}</span> · {plural(c.filesChanged, "file")} · <span className="text-success">+{c.insertions}</span>{" "}
                    <span className="text-danger">−{c.deletions}</span>
                  </span>
                </span>
              </button>
            </li>
          ))}
        </ol>
      </Panel>
      <div className="min-w-0">{diff.data !== undefined ? <DiffView diff={diff.data} /> : <LoadingRows rows={4} />}</div>
    </div>
  );
}

function Details({ migration: m, startedBy }: { migration: Migration; startedBy: string | undefined }) {
  const onOff = (on: boolean) => <ToneBadge tone={on ? "good" : "muted"}>{on ? "On" : "Off"}</ToneBadge>;
  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <Panel title="Options" description="What this migration was asked to do.">
        <dl className="divide-y">
          <Field label="AI">{onOff(m.options.ai)}</Field>
          <Field label="Retrieval (RAG)">{onOff(m.options.ai && m.options.rag)}</Field>
          <Field label="Project tests">{onOff(!m.options.skipTests)}</Field>
          <Field label="Verify behaviour">{onOff(m.options.verifyBehaviour)}</Field>
          <Field label="AI repair rounds">{m.options.ai ? `Up to ${m.options.maxAiIterations}` : "—"}</Field>
        </dl>
      </Panel>
      <Panel title="Run" description="Where and when it ran.">
        <dl className="divide-y">
          <Field label="Project">
            <Link href={`/projects/${m.projectId}`} className="font-medium text-brand hover:underline">
              {m.projectName}
            </Link>
          </Field>
          <Field label="Playbook"><span className="font-mono text-xs">{m.playbook}</span></Field>
          <Field label="Started by">{startedBy ?? "—"}</Field>
          <Field label="Queued">{dateTime(m.createdAt)}</Field>
          <Field label="Started">{dateTime(m.startedAt)}</Field>
          <Field label="Finished">{dateTime(m.finishedAt)}</Field>
          <Field label="Workspace">
            <span className="flex items-start gap-1">
              <span className="font-mono text-xs break-all">{m.workspace}</span>
              <CopyButton value={m.workspace} label="Copy the workspace path" />
            </span>
          </Field>
        </dl>
      </Panel>
    </div>
  );
}
