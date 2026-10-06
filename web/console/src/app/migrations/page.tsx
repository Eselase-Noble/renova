"use client";

import { useQuery } from "@tanstack/react-query";
import { Workflow } from "lucide-react";
import { useState } from "react";

import { MigrationTable } from "@/components/migration-table";
import { Empty, ErrorState, LoadingRows, PageHeader, Panel, SearchInput, Segmented } from "@/components/page";
import { active, api, type Migration } from "@/lib/api";
import { plural } from "@/lib/format";

type Filter = "all" | "active" | "passed" | "failed" | "stopped";

const FILTERS: Record<Filter, (m: Migration) => boolean> = {
  all: () => true,
  active: (m) => active(m.status),
  passed: (m) => m.status === "PASSED",
  failed: (m) => m.status === "FAILED",
  stopped: (m) => m.status === "ERROR" || m.status === "CANCELLED",
};

export default function MigrationsPage() {
  const migrations = useQuery({
    queryKey: ["migrations"],
    queryFn: api.migrations,
    refetchInterval: (q) => (q.state.data?.some((m) => active(m.status)) ? 3000 : 30_000),
  });
  const [filter, setFilter] = useState<Filter>("all");
  const [query, setQuery] = useState("");
  const all = migrations.data ?? [];
  const q = query.trim().toLowerCase();
  const rows = all.filter((m) => FILTERS[filter](m) && (!q || `${m.projectName} ${m.id} ${m.playbook}`.toLowerCase().includes(q)));
  const count = (f: Filter) => all.filter(FILTERS[f]).length;

  return (
    <>
      <PageHeader title="Migrations" description="Every migration run, newest first. Each one is a workspace you can review stage by stage." />
      {migrations.error ? (
        <ErrorState error={migrations.error} />
      ) : migrations.isPending ? (
        <LoadingRows />
      ) : all.length === 0 ? (
        <Empty title="No migrations yet" icon={Workflow}>
          Start one from a project&apos;s page. It runs in the background on a copy of the project.
        </Empty>
      ) : (
        <Panel bodyClassName="p-0">
          <div className="flex flex-wrap items-center gap-3 border-b px-5 py-3">
            <Segmented
              label="Status"
              value={filter}
              onChange={setFilter}
              options={[
                { value: "all", label: "All", count: count("all") },
                { value: "active", label: "In progress", count: count("active") },
                { value: "passed", label: "Passed", count: count("passed") },
                { value: "failed", label: "Failed", count: count("failed") },
                { value: "stopped", label: "Stopped", count: count("stopped"), title: "Errors and cancelled migrations" },
              ]}
            />
            <SearchInput value={query} onChange={setQuery} placeholder="Filter by project or id" className="w-full sm:ml-auto sm:w-64" />
          </div>
          {rows.length === 0 ? (
            <div className="p-5">
              <Empty title="No migration matches">Change the status or the text to see more.</Empty>
            </div>
          ) : (
            <>
              <MigrationTable migrations={rows} />
              <div className="border-t px-5 py-3 text-[13px] text-muted-foreground">{plural(rows.length, "migration")}</div>
            </>
          )}
        </Panel>
      )}
    </>
  );
}
