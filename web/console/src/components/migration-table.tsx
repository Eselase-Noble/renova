"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";

import { BehaviourBadge, BuildBadge, MigrationStatusBadge } from "@/components/status";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { Migration } from "@/lib/api";
import { ago, duration, tokens } from "@/lib/format";

export function MigrationTable({ migrations, showProject = true }: { migrations: Migration[]; showProject?: boolean }) {
  const router = useRouter();
  return (
    <Table>
      <TableHeader>
        <TableRow>
          {showProject && <TableHead>Project</TableHead>}
          <TableHead>Status</TableHead>
          <TableHead className="hidden md:table-cell">Result</TableHead>
          <TableHead className="hidden lg:table-cell">AI</TableHead>
          <TableHead className="text-right">Started</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {migrations.map((m) => (
          <TableRow key={m.id} className="cursor-pointer" onClick={() => router.push(`/migrations/${m.id}`)}>
            {showProject && (
              <TableCell className="font-medium">
                <Link href={`/migrations/${m.id}`} onClick={(e) => e.stopPropagation()} className="hover:underline">
                  {m.projectName}
                </Link>
              </TableCell>
            )}
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
            <TableCell className="hidden text-muted-foreground lg:table-cell">
              {!m.options.ai
                ? "Off"
                : m.summary
                  ? `${m.summary.aiRequests} requests · ${tokens(m.summary.inputTokens + m.summary.outputTokens)} tokens`
                  : "On"}
            </TableCell>
            <TableCell className="text-right text-muted-foreground">
              <div>{ago(m.createdAt)}</div>
              <div className="text-xs">{duration(m.startedAt, m.finishedAt)}</div>
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
