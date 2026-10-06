"use client";

import { Sparkles } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";

import { BehaviourBadge, BuildBadge, MigrationStatusBadge } from "@/components/status";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { Migration } from "@/lib/api";
import { ago, dateTime, duration, percent, tokens } from "@/lib/format";

export function MigrationTable({ migrations, showProject = true }: { migrations: Migration[]; showProject?: boolean }) {
  const router = useRouter();
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>{showProject ? "Project" : "Migration"}</TableHead>
          <TableHead>Status</TableHead>
          <TableHead className="hidden md:table-cell">Result</TableHead>
          <TableHead className="hidden text-right xl:table-cell">Automated</TableHead>
          <TableHead className="hidden lg:table-cell">AI</TableHead>
          <TableHead className="text-right">Started</TableHead>
          <TableHead className="hidden text-right sm:table-cell">Duration</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {migrations.map((m) => (
          <TableRow key={m.id} className="cursor-pointer" onClick={() => router.push(`/migrations/${m.id}`)}>
            <TableCell>
              <Link href={`/migrations/${m.id}`} onClick={(e) => e.stopPropagation()} className="group block outline-none">
                {showProject && <span className="block font-medium group-hover:underline group-focus-visible:underline">{m.projectName}</span>}
                <span className={showProject ? "block font-mono text-[11px] text-muted-foreground" : "font-mono text-[13px] group-hover:underline"}>{m.id}</span>
              </Link>
            </TableCell>
            <TableCell>
              <MigrationStatusBadge status={m.status} />
            </TableCell>
            <TableCell className="hidden md:table-cell">
              {m.summary ? (
                <div className="flex flex-wrap gap-1">
                  <BuildBadge build={m.summary.build} />
                  {m.options.verifyBehaviour && <BehaviourBadge status={m.summary.behaviour} />}
                </div>
              ) : (
                <span className="text-muted-foreground">{m.error ? "Stopped with an error" : "—"}</span>
              )}
            </TableCell>
            <TableCell className="hidden text-right tabular-nums xl:table-cell">{m.summary ? percent(m.summary.automationRate) : "—"}</TableCell>
            <TableCell className="hidden text-muted-foreground lg:table-cell">
              {!m.options.ai ? (
                "Off"
              ) : (
                <span className="inline-flex items-center gap-1.5">
                  <Sparkles className="size-3.5 text-brand" />
                  {m.summary ? `${m.summary.aiRequests} requests · ${tokens(m.summary.inputTokens + m.summary.outputTokens)} tokens` : "On"}
                </span>
              )}
            </TableCell>
            <TableCell className="text-right text-muted-foreground" title={dateTime(m.createdAt)}>
              {ago(m.createdAt)}
            </TableCell>
            <TableCell className="hidden text-right text-muted-foreground tabular-nums sm:table-cell">{duration(m.startedAt, m.finishedAt)}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
