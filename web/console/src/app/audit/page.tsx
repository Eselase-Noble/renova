"use client";

import { useQuery } from "@tanstack/react-query";
import { Building2, FolderGit2, KeyRound, LogIn, Mail, ScrollText, Settings, Users, Workflow, type LucideIcon } from "lucide-react";
import { useState } from "react";

import { Avatar, Empty, ErrorState, LoadingRows, PageHeader, Panel, SearchInput, Segmented } from "@/components/page";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { api, type AuditEvent } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { ago, dateTime, plural } from "@/lib/format";

const AREAS: Record<string, { label: string; icon: LucideIcon }> = {
  auth: { label: "Sign-in", icon: LogIn },
  project: { label: "Projects", icon: FolderGit2 },
  migration: { label: "Migrations", icon: Workflow },
  settings: { label: "Settings", icon: Settings },
  member: { label: "Members", icon: Users },
  invitation: { label: "Invitations", icon: Mail },
  organisation: { label: "Organisation", icon: Building2 },
};

const ACTIONS: Record<string, string> = {
  "auth.signed_in": "Signed in",
  "auth.password_changed": "Changed their password",
  "project.added": "Added a project",
  "project.removed": "Removed a project",
  "migration.started": "Started a migration",
  "migration.cancelled": "Cancelled a migration",
  "settings.changed": "Changed AI settings",
  "settings.key_set": "Saved an API key",
  "settings.key_removed": "Removed an API key",
  "member.role_changed": "Changed a role",
  "member.removed": "Removed a member",
  "member.left": "Left the organisation",
  "invitation.created": "Invited someone",
  "invitation.revoked": "Revoked an invitation",
  "invitation.accepted": "Accepted an invitation",
  "organisation.created": "Created the organisation",
};

const areaOf = (e: AuditEvent) => e.action.split(".")[0];

export default function AuditPage() {
  const auth = useAuth();
  const allowed = auth.can("ADMIN");
  const events = useQuery({ queryKey: ["audit"], queryFn: () => api.audit(), enabled: allowed, refetchInterval: 30_000 });
  const [area, setArea] = useState("all");
  const [query, setQuery] = useState("");

  if (auth.data && !allowed) {
    return (
      <>
        <PageHeader title="Audit log" />
        <Empty title="Only admins see the audit log" icon={ScrollText}>
          Ask an admin or owner of {auth.data.organisation?.name ?? "the organisation"} if you need an entry from it.
        </Empty>
      </>
    );
  }

  const all = events.data ?? [];
  const q = query.trim().toLowerCase();
  const rows = all.filter(
    (e) => (area === "all" || areaOf(e) === area) && (!q || `${e.actorName} ${ACTIONS[e.action] ?? e.action} ${e.target ?? ""} ${e.detail ?? ""}`.toLowerCase().includes(q)),
  );
  const present = Object.keys(AREAS).filter((a) => all.some((e) => areaOf(e) === a));

  return (
    <>
      <PageHeader
        title="Audit log"
        description="Who did what in this organisation: sign-ins, projects, migrations, settings, keys and membership. Entries are only ever added, and never contain a key or password."
      />
      {events.error ? (
        <ErrorState error={events.error} />
      ) : events.isPending ? (
        <LoadingRows rows={6} />
      ) : (
        <Panel bodyClassName="p-0">
          <div className="flex flex-wrap items-center gap-3 border-b px-5 py-3">
            <Segmented
              label="Area"
              value={area}
              onChange={setArea}
              options={[{ value: "all", label: "All", count: all.length }, ...present.map((a) => ({ value: a, label: AREAS[a].label }))]}
            />
            <SearchInput value={query} onChange={setQuery} placeholder="Filter by person or subject" className="w-full sm:ml-auto sm:w-64" />
          </div>
          {rows.length === 0 ? (
            <div className="p-5">
              <Empty title={all.length ? "No entry matches" : "Nothing recorded yet"} icon={ScrollText}>
                {all.length ? "Change the area or the text to see more." : "Entries appear as people sign in and change things."}
              </Empty>
            </div>
          ) : (
            <>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Who</TableHead>
                    <TableHead>What</TableHead>
                    <TableHead className="hidden md:table-cell">Subject</TableHead>
                    <TableHead className="text-right">When</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {rows.map((e) => {
                    const Icon = AREAS[areaOf(e)]?.icon ?? KeyRound;
                    return (
                      <TableRow key={e.id} className="hover:bg-muted/30">
                        <TableCell>
                          <span className="flex items-center gap-2.5">
                            <Avatar name={e.actorName} className="size-6 text-[10px]" />
                            <span className="font-medium">{e.actorName}</span>
                          </span>
                        </TableCell>
                        <TableCell>
                          <span className="flex items-center gap-2">
                            <Icon className="size-4 shrink-0 text-muted-foreground" />
                            {ACTIONS[e.action] ?? e.action}
                          </span>
                        </TableCell>
                        <TableCell className="hidden max-w-md whitespace-normal md:table-cell">
                          {e.target && <div className="font-medium break-words">{e.target}</div>}
                          {e.detail && <div className="text-xs break-words text-muted-foreground">{e.detail}</div>}
                          {!e.target && !e.detail && <span className="text-muted-foreground">—</span>}
                        </TableCell>
                        <TableCell className="text-right text-muted-foreground" title={e.at}>
                          <div>{ago(e.at)}</div>
                          <div className="text-xs">{dateTime(e.at)}</div>
                        </TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
              <div className="border-t px-5 py-3 text-[13px] text-muted-foreground">
                {plural(rows.length, "entry", "entries")}
                {all.length >= 500 && " · the newest 500 are loaded"}
              </div>
            </>
          )}
        </Panel>
      )}
    </>
  );
}
