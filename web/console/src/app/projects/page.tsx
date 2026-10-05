"use client";

import { useQuery } from "@tanstack/react-query";
import { FolderGit2, Plus } from "lucide-react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense } from "react";

import { AddProjectDialog } from "@/components/add-project-dialog";
import { Empty, ErrorState, LoadingRows, PageHeader } from "@/components/page";
import { MigrationStatusBadge } from "@/components/status";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { api } from "@/lib/api";
import { ago } from "@/lib/format";

export default function ProjectsPage() {
  return (
    <Suspense>
      <Projects />
    </Suspense>
  );
}

function Projects() {
  const router = useRouter();
  const adding = useSearchParams().get("add") === "1";
  const setAdding = (open: boolean) => router.replace(open ? "/projects?add=1" : "/projects");
  const projects = useQuery({ queryKey: ["projects"], queryFn: api.projects });
  const migrations = useQuery({ queryKey: ["migrations"], queryFn: api.migrations });

  return (
    <>
      <PageHeader
        title="Projects"
        description="Legacy projects Renova can assess and migrate."
        actions={
          <Button onClick={() => setAdding(true)}>
            <Plus /> Add project
          </Button>
        }
      />
      {projects.error ? (
        <ErrorState error={projects.error} />
      ) : projects.isPending ? (
        <LoadingRows />
      ) : projects.data.length === 0 ? (
        <Empty title="No projects yet">Add the directory of a legacy project to see its assessment.</Empty>
      ) : (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {projects.data.map((p) => {
            const latest = migrations.data?.find((m) => m.projectId === p.id);
            return (
              <Link key={p.id} href={`/projects/${p.id}`} className="group">
                <Card className="h-full transition-shadow group-hover:ring-foreground/25">
                  <CardHeader>
                    <CardTitle className="flex items-center gap-2">
                      <FolderGit2 className="size-4 text-muted-foreground" />
                      {p.name}
                    </CardTitle>
                    <CardDescription className="truncate font-mono text-xs">{p.path}</CardDescription>
                    <div className="flex flex-wrap items-center gap-2 pt-2">
                      <Badge variant="secondary">{p.ecosystem}</Badge>
                      {latest ? (
                        <>
                          <MigrationStatusBadge status={latest.status} />
                          <span className="text-xs text-muted-foreground">{ago(latest.createdAt)}</span>
                        </>
                      ) : (
                        <span className="text-xs text-muted-foreground">Not migrated yet</span>
                      )}
                    </div>
                  </CardHeader>
                </Card>
              </Link>
            );
          })}
        </div>
      )}
      <AddProjectDialog open={adding} onOpenChange={setAdding} />
    </>
  );
}
