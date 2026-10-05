"use client";

import { useQuery } from "@tanstack/react-query";
import { ArrowRight, Plus } from "lucide-react";
import Link from "next/link";

import { MigrationTable } from "@/components/migration-table";
import { Empty, ErrorState, LoadingRows, PageHeader, Stat } from "@/components/page";
import { buttonVariants } from "@/components/ui/button";
import { Card, CardAction, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { tokens } from "@/lib/format";

export default function Dashboard() {
  const auth = useAuth();
  const projects = useQuery({ queryKey: ["projects"], queryFn: api.projects });
  const migrations = useQuery({
    queryKey: ["migrations"],
    queryFn: api.migrations,
    refetchInterval: (q) => (q.state.data?.some((m) => m.status === "RUNNING" || m.status === "QUEUED") ? 3000 : false),
  });

  if (projects.error || migrations.error) return <ErrorState error={projects.error ?? migrations.error} />;

  const all = migrations.data ?? [];
  const finished = all.filter((m) => m.summary);
  const passed = all.filter((m) => m.status === "PASSED").length;
  const sameBehaviour = finished.filter((m) => m.summary?.behaviour === "SAME").length;
  const checkedBehaviour = finished.filter((m) => m.summary?.behaviour && m.summary.behaviour !== "SKIPPED").length;
  const tokensUsed = finished.reduce((n, m) => n + (m.summary?.inputTokens ?? 0) + (m.summary?.outputTokens ?? 0), 0);

  return (
    <>
      <PageHeader
        title="Dashboard"
        description={`Legacy projects, their assessments and migrations in ${auth.data?.organisation?.name ?? "your organisation"}.`}
        actions={
          auth.can("ADMIN") && (
            <Link href="/projects?add=1" className={buttonVariants()}>
              <Plus /> Add project
            </Link>
          )
        }
      />
      <div className="mb-6 grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat label="Projects" value={projects.data?.length ?? "—"} />
        <Stat label="Migrations passed" value={all.length ? `${passed}/${all.length}` : "—"} hint="Build, tests and behaviour" />
        <Stat
          label="Same behaviour"
          value={checkedBehaviour ? `${sameBehaviour}/${checkedBehaviour}` : "—"}
          hint="Original and migrated app compared"
        />
        <Stat label="AI tokens used" value={tokens(tokensUsed)} hint="On your own key" />
      </div>
      <Card>
        <CardHeader>
          <CardTitle>Recent migrations</CardTitle>
          <CardAction>
            <Link href="/migrations" className={buttonVariants({ variant: "ghost", size: "sm" })}>
              All migrations <ArrowRight />
            </Link>
          </CardAction>
        </CardHeader>
        <CardContent>
          {migrations.isPending ? (
            <LoadingRows />
          ) : all.length === 0 ? (
            <Empty title="No migrations yet">Add a project, review its assessment, then start a migration.</Empty>
          ) : (
            <MigrationTable migrations={all.slice(0, 8)} />
          )}
        </CardContent>
      </Card>
    </>
  );
}
