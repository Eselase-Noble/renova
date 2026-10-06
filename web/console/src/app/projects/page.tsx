"use client";

import { useQuery } from "@tanstack/react-query";
import { ArrowRight, FolderGit2, LayoutGrid, List, Plus } from "lucide-react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";

import { AddProjectDialog } from "@/components/add-project-dialog";
import { CopyButton, Empty, ErrorState, LoadingRows, PageHeader, SearchInput, Segmented } from "@/components/page";
import { MigrationStatusBadge, ToneBadge } from "@/components/status";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useStored } from "@/hooks/use-stored";
import { api, type Migration, type Project } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { ago, dateTime, percent, plural } from "@/lib/format";

export default function ProjectsPage() {
  return (
    <Suspense>
      <Projects />
    </Suspense>
  );
}

interface Row {
  project: Project;
  latest: Migration | undefined;
  runs: number;
}

function Projects() {
  const router = useRouter();
  const canAdd = useAuth().can("ADMIN");
  const addRequested = useSearchParams().get("add") === "1";
  const adding = canAdd && addRequested;
  const setAdding = (open: boolean) => router.replace(open ? "/projects?add=1" : "/projects");
  const projects = useQuery({ queryKey: ["projects"], queryFn: api.projects });
  const migrations = useQuery({ queryKey: ["migrations"], queryFn: api.migrations });
  const [query, setQuery] = useState("");
  const [view, setView] = useStored("renova.projects.view", "grid");

  const rows: Row[] = (projects.data ?? [])
    .filter((p) => `${p.name} ${p.path} ${p.ecosystem}`.toLowerCase().includes(query.trim().toLowerCase()))
    .sort((a, b) => a.name.localeCompare(b.name))
    .map((project) => {
      const own = (migrations.data ?? []).filter((m) => m.projectId === project.id);
      return { project, latest: own[0], runs: own.length };
    });

  return (
    <>
      <PageHeader
        title="Projects"
        description="Legacy projects Renova can assess and migrate. A project is only ever read; migrations work on copies."
        actions={
          canAdd && (
            <Button onClick={() => setAdding(true)}>
              <Plus /> Add project
            </Button>
          )
        }
      />
      {projects.error ? (
        <ErrorState error={projects.error} />
      ) : projects.isPending ? (
        <LoadingRows />
      ) : projects.data.length === 0 ? (
        <Empty
          title="No projects yet"
          icon={FolderGit2}
          action={
            canAdd && (
              <Button onClick={() => setAdding(true)}>
                <Plus /> Add project
              </Button>
            )
          }
        >
          {canAdd ? "Add the folder of a legacy project to see what a migration would involve." : "An admin of your organisation can add projects."}
        </Empty>
      ) : (
        <div className="space-y-4">
          <div className="flex flex-wrap items-center gap-3">
            <SearchInput value={query} onChange={setQuery} placeholder="Filter projects" className="w-full sm:w-72" />
            <span className="text-[13px] text-muted-foreground">{plural(rows.length, "project")}</span>
            <div className="ml-auto">
              <Segmented
                label="View"
                value={view}
                onChange={setView}
                options={[
                  { value: "grid", label: <LayoutGrid />, title: "Cards" },
                  { value: "list", label: <List />, title: "Table" },
                ]}
              />
            </div>
          </div>
          {rows.length === 0 ? (
            <Empty title="No project matches">Nothing has “{query}” in its name or path.</Empty>
          ) : view === "list" ? (
            <ProjectTable rows={rows} />
          ) : (
            <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
              {rows.map((row) => (
                <ProjectCard key={row.project.id} {...row} />
              ))}
            </div>
          )}
        </div>
      )}
      <AddProjectDialog open={adding} onOpenChange={setAdding} />
    </>
  );
}

function ProjectCard({ project: p, latest, runs }: Row) {
  return (
    <Link
      href={`/projects/${p.id}`}
      className="group flex flex-col rounded-xl border bg-card shadow-(--shadow-card) outline-none transition-[border-color,box-shadow] hover:border-brand/50 hover:shadow-md focus-visible:ring-2 focus-visible:ring-ring"
    >
      <div className="flex items-start gap-3 p-5">
        <span className="grid size-9 shrink-0 place-items-center rounded-lg bg-brand/10 text-brand">
          <FolderGit2 className="size-[18px]" />
        </span>
        <div className="min-w-0 flex-1">
          <div className="truncate text-[15px] font-semibold tracking-tight">{p.name}</div>
          <div className="truncate font-mono text-xs text-muted-foreground" title={p.path}>
            {p.path}
          </div>
        </div>
        <ArrowRight className="mt-1 size-4 shrink-0 text-muted-foreground opacity-0 transition-opacity group-hover:opacity-100" />
      </div>
      <div className="flex flex-wrap items-center gap-1.5 px-5 pb-4">
        <ToneBadge tone="muted" className="capitalize">{p.ecosystem}</ToneBadge>
        <ToneBadge tone="muted" className="font-mono font-normal">{p.playbook}</ToneBadge>
      </div>
      <div className="mt-auto flex items-center justify-between gap-3 rounded-b-xl border-t bg-muted/30 px-5 py-3 text-xs text-muted-foreground">
        {latest ? (
          <>
            <span className="flex items-center gap-2">
              <MigrationStatusBadge status={latest.status} />
              <span title={dateTime(latest.createdAt)}>{ago(latest.createdAt)}</span>
            </span>
            <span className="tabular-nums">{latest.summary ? `${percent(latest.summary.automationRate)} automated · ` : ""}{plural(runs, "run")}</span>
          </>
        ) : (
          <span>Not migrated yet</span>
        )}
      </div>
    </Link>
  );
}

function ProjectTable({ rows }: { rows: Row[] }) {
  const router = useRouter();
  return (
    <div className="overflow-hidden rounded-xl border bg-card shadow-(--shadow-card)">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Project</TableHead>
            <TableHead className="hidden md:table-cell">Playbook</TableHead>
            <TableHead>Latest migration</TableHead>
            <TableHead className="hidden text-right lg:table-cell">Automated</TableHead>
            <TableHead className="hidden text-right sm:table-cell">Runs</TableHead>
            <TableHead className="hidden text-right lg:table-cell">Added</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map(({ project: p, latest, runs }) => (
            <TableRow key={p.id} className="cursor-pointer" onClick={() => router.push(`/projects/${p.id}`)}>
              <TableCell className="max-w-xs">
                <Link href={`/projects/${p.id}`} onClick={(e) => e.stopPropagation()} className="block truncate font-medium hover:underline">
                  {p.name}
                </Link>
                <span className="flex items-center gap-1">
                  <span className="truncate font-mono text-[11px] text-muted-foreground">{p.path}</span>
                  <CopyButton value={p.path} label="Copy path" className="size-5" />
                </span>
              </TableCell>
              <TableCell className="hidden font-mono text-xs text-muted-foreground md:table-cell">{p.playbook}</TableCell>
              <TableCell>
                {latest ? (
                  <span className="flex items-center gap-2">
                    <MigrationStatusBadge status={latest.status} />
                    <span className="hidden text-xs text-muted-foreground sm:inline">{ago(latest.createdAt)}</span>
                  </span>
                ) : (
                  <span className="text-muted-foreground">Not migrated yet</span>
                )}
              </TableCell>
              <TableCell className="hidden text-right tabular-nums lg:table-cell">{latest?.summary ? percent(latest.summary.automationRate) : "—"}</TableCell>
              <TableCell className="hidden text-right tabular-nums sm:table-cell">{runs}</TableCell>
              <TableCell className="hidden text-right text-muted-foreground lg:table-cell" title={dateTime(p.createdAt)}>
                {ago(p.createdAt)}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}
