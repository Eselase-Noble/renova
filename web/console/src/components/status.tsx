import { Ban, CheckCircle2, CircleDashed, Clock, Loader2, TriangleAlert, XCircle } from "lucide-react";

import type { BehaviourStatus, MigrationStatus } from "@/lib/api";
import { cn } from "@/lib/utils";

const TONES = {
  good: "border-success/25 bg-success/10 text-success",
  bad: "border-danger/25 bg-danger/10 text-danger",
  warn: "border-warning/30 bg-warning/10 text-warning",
  busy: "border-info/25 bg-info/10 text-info",
  brand: "border-brand/25 bg-brand/10 text-brand",
  muted: "border-border bg-muted/60 text-muted-foreground",
};

export type Tone = keyof typeof TONES;

export function ToneBadge({ tone, children, className }: { tone: Tone; children: React.ReactNode; className?: string }) {
  return (
    <span
      className={cn(
        "inline-flex h-[22px] w-fit shrink-0 items-center gap-1 rounded-md border px-1.5 text-xs font-medium tracking-normal whitespace-nowrap [&>svg]:size-3",
        TONES[tone],
        className,
      )}
    >
      {children}
    </span>
  );
}

export const STATUS_LABEL: Record<MigrationStatus, string> = {
  PASSED: "Passed",
  FAILED: "Failed",
  ERROR: "Error",
  RUNNING: "Running",
  QUEUED: "Queued",
  CANCELLED: "Cancelled",
};

export const STATUS_TONE: Record<MigrationStatus, Tone> = {
  PASSED: "good",
  FAILED: "bad",
  ERROR: "bad",
  RUNNING: "busy",
  QUEUED: "muted",
  CANCELLED: "muted",
};

export function StatusIcon({ status, className }: { status: MigrationStatus; className?: string }) {
  const Icon = { PASSED: CheckCircle2, FAILED: XCircle, ERROR: TriangleAlert, RUNNING: Loader2, QUEUED: Clock, CANCELLED: Ban }[status];
  return <Icon className={cn(status === "RUNNING" && "animate-spin", className)} />;
}

export function MigrationStatusBadge({ status }: { status: MigrationStatus }) {
  return (
    <ToneBadge tone={STATUS_TONE[status]}>
      <StatusIcon status={status} />
      {STATUS_LABEL[status]}
    </ToneBadge>
  );
}

/** A coloured dot for dense lists where a badge would be too heavy; always shown beside the status text. */
export function StatusDot({ status, className }: { status: MigrationStatus | null; className?: string }) {
  const colour = !status
    ? "bg-muted-foreground/40"
    : { PASSED: "bg-success", FAILED: "bg-danger", ERROR: "bg-danger", RUNNING: "bg-info animate-status", QUEUED: "bg-muted-foreground/60", CANCELLED: "bg-muted-foreground/40" }[status];
  return <span aria-hidden className={cn("inline-block size-2 shrink-0 rounded-full", colour, className)} />;
}

export function BehaviourBadge({ status }: { status: BehaviourStatus | null | undefined }) {
  if (!status) return <ToneBadge tone="muted"><CircleDashed />Not checked</ToneBadge>;
  const tone: Tone = status === "SAME" ? "good" : status === "SKIPPED" ? "muted" : status === "DIFFERENT" ? "warn" : "bad";
  const label = { SAME: "Same behaviour", DIFFERENT: "Behaviour differs", SKIPPED: "Not compared", FAILED: "Comparison failed" }[status];
  const Icon = { SAME: CheckCircle2, DIFFERENT: TriangleAlert, SKIPPED: CircleDashed, FAILED: XCircle }[status];
  return <ToneBadge tone={tone}><Icon />{label}</ToneBadge>;
}

export function BuildBadge({ build }: { build: "PASSES" | "FAILS" | "NOT_VERIFIED" | undefined }) {
  if (build === "PASSES") return <ToneBadge tone="good"><CheckCircle2 />Build passes</ToneBadge>;
  if (build === "FAILS") return <ToneBadge tone="bad"><XCircle />Build fails</ToneBadge>;
  return <ToneBadge tone="muted"><CircleDashed />Not built</ToneBadge>;
}
