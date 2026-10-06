import { Check, Minus, TriangleAlert, X } from "lucide-react";

import type { Migration } from "@/lib/api";
import { cn } from "@/lib/utils";

type PhaseState = "done" | "current" | "pending" | "skipped" | "failed" | "warn";

export interface Phase {
  key: string;
  label: string;
  state: PhaseState;
  /** The last progress line that belongs to the phase. */
  note?: string;
}

const PHASES: { key: string; label: string; starts: RegExp; when?: (m: Migration) => boolean }[] = [
  { key: "plan", label: "Plan", starts: /^(Queued|Plan:|Copying project)/ },
  { key: "rewrite", label: "Rewrite", starts: /^Stage (recipe|replace|maven)/ },
  { key: "ai", label: "AI edits", starts: /^Stage ai/, when: (m) => m.options.ai },
  { key: "guards", label: "Guards", starts: /^(Checking \d+ guard|Stage guard)/ },
  { key: "build", label: "Build and tests", starts: /^Verifying build/ },
  { key: "repair", label: "AI repair", starts: /^Build fails with/, when: (m) => m.options.ai && m.options.maxAiIterations > 0 },
  { key: "behaviour", label: "Behaviour", starts: /^(Verifying behaviour|Starting |Sending |Behaviour differs)/, when: (m) => m.options.verifyBehaviour },
];

/** Where a migration is, read from its progress lines: each phase starts at a line the engine always writes. */
export function phases(migration: Migration, progress: string[]): Phase[] {
  const wanted = PHASES.filter((p) => !p.when || p.when(migration));
  const notes = new Map<string, string>();
  let reached = -1;
  for (const line of progress) {
    const i = wanted.findIndex((p) => p.starts.test(line));
    if (i >= 0) {
      reached = Math.max(reached, i);
      notes.set(wanted[i].key, line);
    } else if (reached >= 0 && !/^(Finished|Error|Cancelled)/.test(line)) {
      notes.set(wanted[reached].key, line);
    }
  }
  const over = migration.status !== "RUNNING" && migration.status !== "QUEUED";
  const stopped = migration.status === "ERROR" || migration.status === "CANCELLED";
  const s = migration.summary;
  return wanted.map((p, i) => {
    let state: PhaseState = !notes.has(p.key) ? (over ? "skipped" : "pending") : i === reached && !over ? "current" : "done";
    if (over && i === reached && stopped) state = "failed";
    if (over && p.key === "build" && s?.build === "FAILS") state = "failed";
    if (over && p.key === "repair" && notes.has(p.key) && s?.build === "FAILS") state = "warn";
    if (over && p.key === "behaviour" && s?.behaviour === "DIFFERENT") state = "warn";
    if (over && p.key === "behaviour" && s?.behaviour === "FAILED") state = "failed";
    if (migration.status === "QUEUED") state = "pending";
    return { key: p.key, label: p.label, state, note: notes.get(p.key)?.replace(/ to \/.*$/, "") };
  });
}

const DOT: Record<PhaseState, string> = {
  done: "border-success bg-success text-background",
  current: "border-info bg-info/15 text-info",
  pending: "border-border bg-card text-muted-foreground",
  skipped: "border-dashed border-border bg-card text-muted-foreground/60",
  failed: "border-danger bg-danger text-background",
  warn: "border-warning bg-warning text-background",
};

const STATE_NAME: Record<PhaseState, string> = { done: "done", current: "in progress", pending: "not started", skipped: "not run", failed: "failed", warn: "needs attention" };

/** The phases of a migration as a row of steps joined by a line. */
export function Pipeline({ phases: list }: { phases: Phase[] }) {
  return (
    <ol className="flex w-full overflow-x-auto pb-1">
      {list.map((p, i) => (
        <li key={p.key} className="relative flex min-w-24 flex-1 flex-col items-center gap-2 text-center" aria-label={`${p.label}: ${STATE_NAME[p.state]}`}>
          {i > 0 && (
            <span
              aria-hidden
              className={cn("absolute top-3 right-1/2 left-[-50%] h-0.5", p.state === "pending" || p.state === "skipped" ? "bg-border" : "bg-success/60")}
            />
          )}
          <span className={cn("relative z-10 grid size-6 place-items-center rounded-full border-2 text-[11px] font-semibold", DOT[p.state])}>
            {p.state === "done" ? (
              <Check className="size-3.5" strokeWidth={3} />
            ) : p.state === "failed" ? (
              <X className="size-3.5" strokeWidth={3} />
            ) : p.state === "warn" ? (
              <TriangleAlert className="size-3" strokeWidth={2.5} />
            ) : p.state === "skipped" ? (
              <Minus className="size-3" />
            ) : p.state === "current" ? (
              <span className="size-2 rounded-full bg-info animate-status" />
            ) : (
              i + 1
            )}
          </span>
          <span className="px-1">
            <span className={cn("block text-[13px] font-medium", (p.state === "pending" || p.state === "skipped") && "text-muted-foreground")}>{p.label}</span>
            <span className="block text-[11px] text-muted-foreground">{STATE_NAME[p.state]}</span>
          </span>
        </li>
      ))}
    </ol>
  );
}

/** How far along a migration is, as a share of its phases, for compact lists. */
export function progressShare(list: Phase[]): number {
  const done = list.filter((p) => p.state === "done").length + (list.some((p) => p.state === "current") ? 0.5 : 0);
  return list.length ? done / list.length : 0;
}
