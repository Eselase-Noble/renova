"use client";

import { Bot, ChevronRight, Cog, OctagonAlert, TriangleAlert, User } from "lucide-react";
import { Fragment, useMemo, useState } from "react";

import { BarList, Gauge, StackedBar } from "@/components/charts";
import { Empty, Panel, SearchInput, Segmented, Stat } from "@/components/page";
import { ToneBadge, type Tone } from "@/components/status";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { Assessment, PlanStep } from "@/lib/api";
import { CATEGORY_NAMES, plural, RESOLVER_NAMES, resolverOf, STRATEGY_NAMES, type Resolver } from "@/lib/format";
import { cn } from "@/lib/utils";

const STRATEGY_TONE: Record<string, Tone> = { recipe: "good", replace: "good", maven: "good", gradle: "good", dotnet: "good", "dotnet-source": "good", composer: "good", rector: "good", ai: "brand", manual: "warn" };
const STRATEGY_ICON: Record<Resolver, typeof Cog> = { automatic: Cog, ai: Bot, person: User };

export function StrategyBadge({ strategy }: { strategy: string }) {
  const Icon = STRATEGY_ICON[resolverOf(strategy)];
  return (
    <ToneBadge tone={STRATEGY_TONE[strategy] ?? "muted"}>
      <Icon />
      {STRATEGY_NAMES[strategy] ?? strategy}
    </ToneBadge>
  );
}

// Fixed colours per entity: a category keeps its hue whatever else is on screen.
const CATEGORY_COLOR: Record<string, string> = {
  A: "var(--series-1)",
  B: "var(--series-2)",
  C: "var(--series-3)",
  D: "var(--series-4)",
  E: "var(--series-5)",
};
const RESOLVER_COLOR: Record<Resolver, string> = { automatic: "var(--series-1)", ai: "var(--series-2)", person: "var(--series-3)" };

const list = (value: unknown) => (Array.isArray(value) ? value.join(", ") : value === undefined || value === null ? "" : String(value));

export function AssessmentView({ assessment }: { assessment: Assessment }) {
  const { summary, plan, project } = assessment;
  const blockers = plan.filter((s) => s.severity === "BLOCKER").length;
  const byResolver: Record<Resolver, number> = { automatic: 0, ai: 0, person: 0 };
  for (const [strategy, count] of Object.entries(summary.byStrategy)) byResolver[resolverOf(strategy)] += count;
  const stepsFor = (r: Resolver) => plan.filter((s) => resolverOf(s.strategy) === r).length;
  const facts = project.facts;
  const profile = [
    ["Ecosystem", project.ecosystem],
    ["Build", list(facts.buildTools)],
    ["Java", list(facts.javaVersions)],
    ["Runs on", list(facts.containers)],
    ["Languages", list(facts.languages)],
    ["Target frameworks", list(facts.targetFrameworks)],
    ["Project kinds", list(facts.kinds)],
    ["PHP", list(facts.phpVersions)],
    ["Framework", list(facts.frameworks).replace(/^none$/, "")],
    ["Modules", project.modules.length ? String(project.modules.length) : ""],
  ].filter(([, value]) => value);

  return (
    <div className="space-y-6">
      {assessment.warnings.length > 0 && (
        <Alert>
          <TriangleAlert />
          <AlertTitle>The analysis reported {plural(assessment.warnings.length, "warning")}</AlertTitle>
          <AlertDescription>
            <ul className="list-disc space-y-0.5 pl-4">
              {assessment.warnings.map((w) => (
                <li key={w}>{w}</li>
              ))}
            </ul>
          </AlertDescription>
        </Alert>
      )}
      <div className="grid gap-6 xl:grid-cols-3">
        <Panel title="Automation" description="How much of the migration needs no human decision." className="xl:col-span-2" bodyClassName="flex items-center p-5">
          <div className="flex w-full flex-col items-center gap-6 sm:flex-row sm:items-center sm:gap-8">
            <Gauge value={summary.automationRate} label="Automation rate" caption="automated" />
            <div className="w-full min-w-0 flex-1 space-y-4">
              <p className="text-sm text-muted-foreground">
                <span className="font-medium text-foreground">{byResolver.automatic + byResolver.ai} of {summary.findings} findings</span> are resolved by
                recipes, rules or AI and checked by the real build. {byResolver.person > 0 ? `${plural(byResolver.person, "finding")} need${byResolver.person === 1 ? "s" : ""} a person, with guidance.` : "None are left to a person."}
              </p>
              <StackedBar
                label="Findings by who resolves them"
                unit="findings"
                segments={(["automatic", "ai", "person"] as const).map((r) => ({ key: r, label: RESOLVER_NAMES[r], value: byResolver[r], color: RESOLVER_COLOR[r] }))}
              />
            </div>
          </div>
        </Panel>
        <Panel title="Project profile" description={assessment.playbook.name}>
          <dl className="divide-y text-sm">
            {profile.map(([label, value]) => (
              <div key={label} className="flex items-baseline justify-between gap-4 py-2 first:pt-0">
                <dt className="text-[13px] text-muted-foreground">{label}</dt>
                <dd className="truncate text-right font-medium capitalize">{value}</dd>
              </div>
            ))}
            <div className="flex items-baseline justify-between gap-4 py-2 last:pb-0">
              <dt className="text-[13px] text-muted-foreground">Playbook</dt>
              <dd className="truncate text-right font-mono text-xs">{assessment.playbook.id} · v{assessment.playbook.version}</dd>
            </div>
          </dl>
        </Panel>
      </div>
      <div className="grid grid-cols-2 gap-4 xl:grid-cols-4">
        <Stat label="Findings" value={summary.findings} hint="Places in the code a rule matched" />
        <Stat label="Plan steps" value={plan.length} hint={`${stepsFor("automatic")} automatic · ${stepsFor("ai")} AI · ${stepsFor("person")} for a person`} />
        <Stat label="Blockers" icon={OctagonAlert} value={blockers} hint="Steps the migration cannot succeed without" />
        <Stat label="For a person" icon={User} value={stepsFor("person")} hint="Decisions left to your team, with guidance" />
      </div>
      <Panel title="Findings by category" description="Plan steps run in the order A → E → B → C → D.">
        <BarList
          unit="findings"
          rows={Object.entries(summary.byCategory)
            .sort(([a], [b]) => a.localeCompare(b))
            .map(([category, count]) => ({ key: category, code: category, label: CATEGORY_NAMES[category] ?? category, value: count, color: CATEGORY_COLOR[category] ?? "var(--series-1)" }))}
        />
      </Panel>
      {project.modules.length > 1 && (
        <Panel title="Modules" description="Build modules the migration covers." bodyClassName="p-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Module</TableHead>
                <TableHead>Packaging</TableHead>
                <TableHead className="hidden md:table-cell">Dependencies</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {project.modules.map((m) => {
                const moduleFacts = (m as { facts?: Record<string, unknown> }).facts ?? {};
                const dependencies = Array.isArray(moduleFacts.dependencies) ? (moduleFacts.dependencies as string[]) : [];
                return (
                  <TableRow key={m.path || m.name} className="hover:bg-transparent">
                    <TableCell>
                      <div className="font-medium">{m.name}</div>
                      <div className="font-mono text-[11px] text-muted-foreground">{m.path || "."}</div>
                    </TableCell>
                    <TableCell className="text-muted-foreground">{list(moduleFacts.packaging) || "—"}</TableCell>
                    <TableCell className="hidden max-w-lg font-mono text-xs whitespace-normal text-muted-foreground md:table-cell">
                      {dependencies.slice(0, 6).join(", ") || "—"}
                      {dependencies.length > 6 && ` and ${dependencies.length - 6} more`}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </Panel>
      )}
    </div>
  );
}

type ResolverFilter = "all" | Resolver;

/** The ordered plan: each step, who resolves it and the files it touches. */
export function PlanView({ plan }: { plan: PlanStep[] }) {
  const [open, setOpen] = useState<number | null>(null);
  const [filter, setFilter] = useState<ResolverFilter>("all");
  const [query, setQuery] = useState("");
  const count = (r: Resolver) => plan.filter((s) => resolverOf(s.strategy) === r).length;
  const steps = useMemo(() => {
    const q = query.trim().toLowerCase();
    return plan.filter(
      (s) => (filter === "all" || resolverOf(s.strategy) === filter) && (!q || `${s.title} ${s.rule} ${s.files.join(" ")}`.toLowerCase().includes(q)),
    );
  }, [plan, filter, query]);

  return (
    <Panel
      title="Migration plan"
      description="Recipes and rules are deterministic. AI edits are checked by the build. The rest is left to a person, with guidance."
      bodyClassName="p-0"
    >
      <div className="flex flex-wrap items-center gap-3 border-b px-5 py-3">
        <Segmented
          label="Resolved by"
          value={filter}
          onChange={setFilter}
          options={[
            { value: "all", label: "All", count: plan.length },
            { value: "automatic", label: "Automatic", count: count("automatic") },
            { value: "ai", label: "AI", count: count("ai") },
            { value: "person", label: "A person", count: count("person") },
          ]}
        />
        <SearchInput value={query} onChange={setQuery} placeholder="Filter steps or files" className="w-full sm:ml-auto sm:w-64" />
      </div>
      {steps.length === 0 ? (
        <div className="p-5">
          <Empty title="No step matches">Change the filter to see the rest of the plan.</Empty>
        </div>
      ) : (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead className="w-12">#</TableHead>
              <TableHead>Step</TableHead>
              <TableHead className="hidden sm:table-cell">Category</TableHead>
              <TableHead>Resolved by</TableHead>
              <TableHead className="text-right">Places</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {steps.map((step) => (
              <Fragment key={step.order}>
                <TableRow className="cursor-pointer" aria-expanded={open === step.order} onClick={() => setOpen(open === step.order ? null : step.order)}>
                  <TableCell className="text-muted-foreground tabular-nums">{step.order}</TableCell>
                  <TableCell className="max-w-xl whitespace-normal">
                    <span className="flex items-start gap-1.5">
                      <ChevronRight className={cn("mt-0.5 size-4 shrink-0 text-muted-foreground transition-transform", open === step.order && "rotate-90")} />
                      <span>
                        <span className="font-medium">{step.title}</span>
                        {step.severity === "BLOCKER" && <ToneBadge tone="bad" className="ml-2 h-[18px] align-middle text-[11px]">Blocker</ToneBadge>}
                      </span>
                    </span>
                  </TableCell>
                  <TableCell className="hidden sm:table-cell">
                    <span className="inline-flex items-center gap-2 text-muted-foreground" title={CATEGORY_NAMES[step.category]}>
                      <span aria-hidden className="size-2 rounded-[2px]" style={{ background: CATEGORY_COLOR[step.category] }} />
                      <span className="font-mono text-xs font-semibold text-foreground">{step.category}</span>
                      <span className="hidden xl:inline">{CATEGORY_NAMES[step.category]}</span>
                    </span>
                  </TableCell>
                  <TableCell>
                    <StrategyBadge strategy={step.strategy} />
                  </TableCell>
                  <TableCell className="text-right tabular-nums">{step.occurrences}</TableCell>
                </TableRow>
                {open === step.order && (
                  <TableRow className="bg-muted/30 hover:bg-muted/30">
                    <TableCell />
                    <TableCell colSpan={4} className="space-y-3 py-4 whitespace-normal">
                      {step.hint && <p className="max-w-3xl text-sm">{step.hint}</p>}
                      <div className="grid gap-x-8 gap-y-3 text-xs sm:grid-cols-[auto_1fr]">
                        <span className="text-muted-foreground">Rule</span>
                        <span className="font-mono">{step.rule}</span>
                        {step.recipes && step.recipes.length > 0 && (
                          <>
                            <span className="text-muted-foreground">Recipes</span>
                            <span className="font-mono break-all">{step.recipes.join(", ")}</span>
                          </>
                        )}
                        <span className="text-muted-foreground">Files</span>
                        <ul className="space-y-0.5 font-mono">
                          {step.files.slice(0, 12).map((f) => (
                            <li key={f} className="break-all">{f}</li>
                          ))}
                          {step.files.length > 12 && <li className="font-sans text-muted-foreground">and {step.files.length - 12} more</li>}
                        </ul>
                      </div>
                    </TableCell>
                  </TableRow>
                )}
              </Fragment>
            ))}
          </TableBody>
        </Table>
      )}
    </Panel>
  );
}

const PAGE = 100;

/** Every place a rule matched, with filters; the web counterpart of the desktop app's Findings tab. */
export function FindingsView({ assessment }: { assessment: Assessment }) {
  const [query, setQuery] = useState("");
  const [category, setCategory] = useState("all");
  const [shown, setShown] = useState(PAGE);
  const steps = useMemo(() => new Map(assessment.plan.map((s) => [s.rule, s])), [assessment.plan]);
  const categories = Object.keys(assessment.summary.byCategory).sort();
  const rows = useMemo(() => {
    const q = query.trim().toLowerCase();
    return assessment.findings.filter(([rule, file, , evidence]) => {
      const step = steps.get(rule);
      if (category !== "all" && step?.category !== category) return false;
      return !q || `${rule} ${file} ${evidence ?? ""} ${step?.title ?? ""}`.toLowerCase().includes(q);
    });
  }, [assessment.findings, steps, query, category]);

  return (
    <Panel title="Findings" description="Every place in the code a playbook rule matched." bodyClassName="p-0">
      <div className="flex flex-wrap items-center gap-3 border-b px-5 py-3">
        <Segmented
          label="Category"
          value={category}
          onChange={(v) => {
            setCategory(v);
            setShown(PAGE);
          }}
          options={[
            { value: "all", label: "All", count: assessment.findings.length },
            ...categories.map((c) => ({ value: c, label: c, count: assessment.summary.byCategory[c], title: CATEGORY_NAMES[c] })),
          ]}
        />
        <SearchInput
          value={query}
          onChange={(v) => {
            setQuery(v);
            setShown(PAGE);
          }}
          placeholder="Filter by file, rule or text"
          className="w-full sm:ml-auto sm:w-72"
        />
      </div>
      {rows.length === 0 ? (
        <div className="p-5">
          <Empty title="No finding matches">Change the category or the text to see more.</Empty>
        </div>
      ) : (
        <>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>File</TableHead>
                <TableHead>Rule</TableHead>
                <TableHead className="hidden lg:table-cell">Found</TableHead>
                <TableHead>Resolved by</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.slice(0, shown).map(([rule, file, line, evidence], i) => {
                const step = steps.get(rule);
                return (
                  <TableRow key={`${rule}:${file}:${line}:${i}`} className="hover:bg-muted/30">
                    <TableCell className="max-w-sm font-mono text-xs whitespace-normal break-all">
                      {file}
                      {line > 0 && <span className="text-muted-foreground">:{line}</span>}
                    </TableCell>
                    <TableCell className="max-w-xs whitespace-normal">
                      <div className="text-[13px]">{step?.title ?? rule}</div>
                      <div className="flex items-center gap-1.5 font-mono text-[11px] text-muted-foreground">
                        {step && <span aria-hidden className="size-2 rounded-[2px]" style={{ background: CATEGORY_COLOR[step.category] }} />}
                        {step ? `${step.category} · ` : ""}
                        {rule}
                      </div>
                    </TableCell>
                    <TableCell className="hidden max-w-sm font-mono text-xs whitespace-normal break-all text-muted-foreground lg:table-cell">{evidence ?? "—"}</TableCell>
                    <TableCell>{step ? <StrategyBadge strategy={step.strategy} /> : "—"}</TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
          <div className="flex items-center justify-between gap-3 border-t px-5 py-3 text-[13px] text-muted-foreground">
            <span>
              Showing {Math.min(shown, rows.length)} of {plural(rows.length, "finding")}
            </span>
            {rows.length > shown && (
              <button type="button" onClick={() => setShown(shown + PAGE)} className="font-medium text-brand outline-none hover:underline focus-visible:underline">
                Show {Math.min(PAGE, rows.length - shown)} more
              </button>
            )}
          </div>
        </>
      )}
    </Panel>
  );
}
