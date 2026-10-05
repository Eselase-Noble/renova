"use client";

import { useQuery } from "@tanstack/react-query";

import { MigrationTable } from "@/components/migration-table";
import { Empty, ErrorState, LoadingRows, PageHeader } from "@/components/page";
import { Card, CardContent } from "@/components/ui/card";
import { api } from "@/lib/api";

export default function MigrationsPage() {
  const migrations = useQuery({
    queryKey: ["migrations"],
    queryFn: api.migrations,
    refetchInterval: (q) => (q.state.data?.some((m) => m.status === "RUNNING" || m.status === "QUEUED") ? 3000 : false),
  });
  return (
    <>
      <PageHeader title="Migrations" description="Every migration run, newest first. Each one is a workspace you can review stage by stage." />
      {migrations.error ? (
        <ErrorState error={migrations.error} />
      ) : (
        <Card>
          <CardContent>
            {migrations.isPending ? (
              <LoadingRows />
            ) : migrations.data.length === 0 ? (
              <Empty title="No migrations yet">Start one from a project&apos;s page.</Empty>
            ) : (
              <MigrationTable migrations={migrations.data} />
            )}
          </CardContent>
        </Card>
      )}
    </>
  );
}
