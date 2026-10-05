import { CheckCircle2, CircleDashed, Clock, Loader2, TriangleAlert, XCircle } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import type { BehaviourStatus, MigrationStatus } from "@/lib/api";
import { cn } from "@/lib/utils";

const TONES = {
  good: "border-emerald-500/30 bg-emerald-500/10 text-emerald-700 dark:text-emerald-400",
  bad: "border-red-500/30 bg-red-500/10 text-red-700 dark:text-red-400",
  warn: "border-amber-500/30 bg-amber-500/10 text-amber-700 dark:text-amber-400",
  busy: "border-sky-500/30 bg-sky-500/10 text-sky-700 dark:text-sky-400",
  muted: "text-muted-foreground",
};

export type Tone = keyof typeof TONES;

export function ToneBadge({ tone, children, className }: { tone: Tone; children: React.ReactNode; className?: string }) {
  return (
    <Badge variant="outline" className={cn("gap-1 font-medium", TONES[tone], className)}>
      {children}
    </Badge>
  );
}

export function MigrationStatusBadge({ status }: { status: MigrationStatus }) {
  switch (status) {
    case "PASSED":
      return <ToneBadge tone="good"><CheckCircle2 className="size-3" />Passed</ToneBadge>;
    case "FAILED":
      return <ToneBadge tone="bad"><XCircle className="size-3" />Failed</ToneBadge>;
    case "ERROR":
      return <ToneBadge tone="bad"><TriangleAlert className="size-3" />Error</ToneBadge>;
    case "RUNNING":
      return <ToneBadge tone="busy"><Loader2 className="size-3 animate-spin" />Running</ToneBadge>;
    default:
      return <ToneBadge tone="muted"><Clock className="size-3" />Queued</ToneBadge>;
  }
}

export function BehaviourBadge({ status }: { status: BehaviourStatus | null | undefined }) {
  if (!status) return <ToneBadge tone="muted"><CircleDashed className="size-3" />Not checked</ToneBadge>;
  const tone: Tone = status === "SAME" ? "good" : status === "SKIPPED" ? "muted" : status === "DIFFERENT" ? "warn" : "bad";
  const label = { SAME: "Same behaviour", DIFFERENT: "Behaviour differs", SKIPPED: "Not compared", FAILED: "Comparison failed" }[status];
  return <ToneBadge tone={tone}>{label}</ToneBadge>;
}

export function BuildBadge({ build }: { build: "PASSES" | "FAILS" | "NOT_VERIFIED" | undefined }) {
  if (build === "PASSES") return <ToneBadge tone="good">Build passes</ToneBadge>;
  if (build === "FAILS") return <ToneBadge tone="bad">Build fails</ToneBadge>;
  return <ToneBadge tone="muted">Not built</ToneBadge>;
}
