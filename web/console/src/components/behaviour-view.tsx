"use client";

import { ChevronRight } from "lucide-react";
import { Fragment, useState } from "react";

import { Panel, Stat } from "@/components/page";
import { BehaviourBadge, ToneBadge } from "@/components/status";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { BehaviourReport, BehaviourResult } from "@/lib/api";
import { cn } from "@/lib/utils";

const label = (r: BehaviourResult) =>
  r.scenario.startsWith("file.") ? `${r.scenario.slice(5)} · step ${r.step}: ${r.method} ${r.path}` : `${r.method} ${r.path}`;

export function BehaviourView({ report }: { report: BehaviourReport }) {
  const differing = report.results.filter((r) => !r.same);
  const databases = Object.entries(report.databases);
  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center gap-2">
        <BehaviourBadge status={report.status} />
        <span className="text-sm text-muted-foreground">{report.summary}</span>
      </div>
      {report.original && (
        <div className="grid grid-cols-2 gap-4 xl:grid-cols-4">
          <Stat label="Original" value={<span className="text-base">{report.original}</span>} hint="Built from the baseline commit" />
          <Stat label="Migrated" value={<span className="text-base">{report.migrated}</span>} hint="Built from the migrated code" />
          <Stat label="Requests compared" value={report.results.length} />
          <Stat label="Answered differently" value={differing.length} />
        </div>
      )}
      {differing.length > 0 && (
        <Panel title="Differences" description="The migrated application answered these requests differently from the original." bodyClassName="p-5 space-y-4">
            {differing.map((r) => (
              <div key={`${r.scenario}:${r.step}`} className="space-y-2 rounded-lg border p-3">
                <div className="font-mono text-sm font-medium">{label(r)}</div>
                <div className="text-xs text-muted-foreground">
                  From {r.source}
                  {r.handler && <> · handled in <span className="font-mono">{r.handler}</span></>}
                </div>
                <ul className="list-disc space-y-1 pl-5 text-sm">
                  {r.differences.map((d) => (
                    <li key={d}>{d}</li>
                  ))}
                </ul>
                <Answers result={r} />
              </div>
            ))}
          </Panel>
      )}
      {databases.length > 0 && (
        <Panel title="Database changes" description="Rows each version added and removed in its own database during the same scenario." bodyClassName="p-5 space-y-2">
            {databases.map(([scenario, diffs]) => (
              <div key={scenario} className="flex flex-col gap-1 text-sm sm:flex-row sm:items-start sm:gap-3">
                <span className="font-mono sm:w-64 sm:shrink-0">{scenario.replace(/^file\./, "")}</span>
                {diffs.length === 0 ? (
                  <ToneBadge tone="good">Same rows</ToneBadge>
                ) : (
                  <ul className="space-y-1">
                    {diffs.map((d) => (
                      <li key={d} className="font-mono text-xs text-warning">{d}</li>
                    ))}
                  </ul>
                )}
              </div>
            ))}
          </Panel>
      )}
      {report.accepted.length > 0 && (
        <Panel title="Accepted changes" description="Listed under accept: in the scenario file as intended.">
            <ul className="list-disc space-y-1 pl-5 text-sm">
              {report.accepted.map((a) => (
                <li key={a}>{a}</li>
              ))}
            </ul>
          </Panel>
      )}
      {report.results.length > 0 && <AllRequests results={report.results} />}
    </div>
  );
}

function AllRequests({ results }: { results: BehaviourResult[] }) {
  const [open, setOpen] = useState<string | null>(null);
  return (
    <Panel title="All requests" bodyClassName="p-0">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Request</TableHead>
              <TableHead className="text-right">Original</TableHead>
              <TableHead className="text-right">Migrated</TableHead>
              <TableHead className="text-right">Result</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {results.map((r) => {
              const key = `${r.scenario}:${r.step}`;
              return (
                <Fragment key={key}>
                  <TableRow className="cursor-pointer" onClick={() => setOpen(open === key ? null : key)}>
                    <TableCell className="max-w-md truncate font-mono text-xs">
                      <ChevronRight className={cn("mr-1 inline size-3.5 transition-transform", open === key && "rotate-90")} />
                      {label(r)}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{r.original.status < 0 ? "—" : r.original.status}</TableCell>
                    <TableCell className="text-right tabular-nums">{r.migrated.status < 0 ? "—" : r.migrated.status}</TableCell>
                    <TableCell className="text-right">
                      {r.same ? <ToneBadge tone="good">Same</ToneBadge> : <ToneBadge tone="warn">Different</ToneBadge>}
                    </TableCell>
                  </TableRow>
                  {open === key && (
                    <TableRow className="hover:bg-transparent">
                      <TableCell colSpan={4} className="whitespace-normal">
                        {r.notes.length > 0 && <p className="mb-2 text-xs text-muted-foreground">{r.notes.join("; ")}</p>}
                        <Answers result={r} />
                      </TableCell>
                    </TableRow>
                  )}
                </Fragment>
              );
            })}
          </TableBody>
        </Table>
      </Panel>
  );
}

function Answers({ result }: { result: BehaviourResult }) {
  return (
    <div className="grid gap-2 md:grid-cols-2">
      {(["original", "migrated"] as const).map((side) => {
        const answer = result[side];
        return (
          <div key={side} className="min-w-0 rounded-lg border bg-muted/40 p-3">
            <div className="mb-1 flex flex-wrap gap-x-2 text-xs text-muted-foreground">
              <span className="font-medium capitalize text-foreground">{side}</span>
              <span>{answer.status < 0 ? answer.error : answer.status}</span>
              {answer.contentType && <span className="truncate">{answer.contentType}</span>}
            </div>
            <pre className="scroll-thin max-h-48 overflow-auto font-mono text-xs whitespace-pre-wrap">{answer.body || "(empty)"}</pre>
          </div>
        );
      })}
    </div>
  );
}
