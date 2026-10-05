"use client";

import { ChevronRight } from "lucide-react";
import { Fragment, useState } from "react";

import { Stat } from "@/components/page";
import { ToneBadge, type Tone } from "@/components/status";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { Assessment, PlanStep } from "@/lib/api";
import { CATEGORY_NAMES, percent, STRATEGY_NAMES } from "@/lib/format";
import { cn } from "@/lib/utils";

const STRATEGY_TONE: Record<string, Tone> = { recipe: "good", replace: "good", maven: "good", ai: "busy", manual: "warn" };

export function StrategyBadge({ strategy }: { strategy: string }) {
  return <ToneBadge tone={STRATEGY_TONE[strategy] ?? "muted"}>{STRATEGY_NAMES[strategy] ?? strategy}</ToneBadge>;
}

export function AssessmentView({ assessment }: { assessment: Assessment }) {
  const { summary, plan } = assessment;
  const manual = plan.filter((s) => s.strategy === "manual").length;
  const max = Math.max(1, ...Object.values(summary.byCategory));
  return (
    <div className="space-y-6">
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat label="Findings" value={summary.findings} hint={`${assessment.findings.length} places in the code`} />
        <Stat label="Automation rate" value={percent(summary.automationRate)} hint="No human decision needed" />
        <Stat label="Plan steps" value={plan.length} hint={`Playbook ${assessment.playbook.id}`} />
        <Stat label="For a person" value={manual} hint="Steps with guidance in the report" />
      </div>
      <Card>
        <CardHeader>
          <CardTitle>Findings by category</CardTitle>
          <CardDescription>Plan steps run in the order A → E → B → C → D.</CardDescription>
        </CardHeader>
        <CardContent className="space-y-2">
          {Object.entries(summary.byCategory)
            .sort(([a], [b]) => a.localeCompare(b))
            .map(([category, count]) => (
              <div key={category} className="grid grid-cols-[2rem_1fr_3rem] items-center gap-3 text-sm sm:grid-cols-[2rem_16rem_1fr_3rem]">
                <span className="font-mono font-semibold">{category}</span>
                <span className="hidden truncate text-muted-foreground sm:block">{CATEGORY_NAMES[category] ?? category}</span>
                <div className="h-2 rounded-full bg-muted">
                  <div className="h-2 rounded-full bg-primary" style={{ width: `${(count / max) * 100}%` }} />
                </div>
                <span className="text-right tabular-nums">{count}</span>
              </div>
            ))}
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle>Migration plan</CardTitle>
          <CardDescription>Who resolves each step: recipes and rules are deterministic; AI edits are checked by the build.</CardDescription>
        </CardHeader>
        <CardContent>
          <PlanTable plan={plan} />
        </CardContent>
      </Card>
      {assessment.warnings.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle>Analysis warnings</CardTitle>
          </CardHeader>
          <CardContent>
            <ul className="list-disc space-y-1 pl-5 text-sm">
              {assessment.warnings.map((w) => (
                <li key={w}>{w}</li>
              ))}
            </ul>
          </CardContent>
        </Card>
      )}
    </div>
  );
}

function PlanTable({ plan }: { plan: PlanStep[] }) {
  const [open, setOpen] = useState<number | null>(null);
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead className="w-10">#</TableHead>
          <TableHead>Step</TableHead>
          <TableHead className="hidden sm:table-cell">Category</TableHead>
          <TableHead>Resolved by</TableHead>
          <TableHead className="text-right">Places</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {plan.map((step) => (
          <Fragment key={step.order}>
            <TableRow className="cursor-pointer" onClick={() => setOpen(open === step.order ? null : step.order)}>
              <TableCell className="text-muted-foreground tabular-nums">{step.order}</TableCell>
              <TableCell className="max-w-md whitespace-normal">
                <span className="flex items-start gap-1">
                  <ChevronRight className={cn("mt-0.5 size-4 shrink-0 transition-transform", open === step.order && "rotate-90")} />
                  <span>
                    {step.title}
                    {step.severity === "BLOCKER" && <span className="ml-2 text-xs text-red-600 dark:text-red-400">blocker</span>}
                  </span>
                </span>
              </TableCell>
              <TableCell className="hidden font-mono sm:table-cell">{step.category}</TableCell>
              <TableCell>
                <StrategyBadge strategy={step.strategy} />
              </TableCell>
              <TableCell className="text-right tabular-nums">{step.occurrences}</TableCell>
            </TableRow>
            {open === step.order && (
              <TableRow className="hover:bg-transparent">
                <TableCell />
                <TableCell colSpan={4} className="space-y-2 pb-4 whitespace-normal">
                  {step.hint && <p className="text-sm">{step.hint}</p>}
                  {step.recipes && step.recipes.length > 0 && (
                    <p className="font-mono text-xs text-muted-foreground">{step.recipes.join(", ")}</p>
                  )}
                  <ul className="space-y-0.5 font-mono text-xs text-muted-foreground">
                    {step.files.slice(0, 12).map((f) => (
                      <li key={f}>{f}</li>
                    ))}
                    {step.files.length > 12 && <li>… and {step.files.length - 12} more</li>}
                  </ul>
                </TableCell>
              </TableRow>
            )}
          </Fragment>
        ))}
      </TableBody>
    </Table>
  );
}
