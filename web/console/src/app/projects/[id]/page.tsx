"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Play, Trash2 } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { use, useState } from "react";
import { toast } from "sonner";

import { AssessmentView } from "@/components/assessment";
import { MigrationTable } from "@/components/migration-table";
import { Empty, ErrorState, LoadingRows, PageHeader } from "@/components/page";
import { StartMigrationDialog } from "@/components/start-migration-dialog";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth";

export default function ProjectPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const project = useQuery({ queryKey: ["project", id], queryFn: () => api.project(id) });
  const assessment = useQuery({ queryKey: ["assessment", id], queryFn: () => api.assessment(id), staleTime: 60_000 });
  const migrations = useQuery({
    queryKey: ["migrations", "project", id],
    queryFn: () => api.projectMigrations(id),
    refetchInterval: (q) => (q.state.data?.some((m) => m.status === "RUNNING" || m.status === "QUEUED") ? 3000 : false),
  });
  const auth = useAuth();
  const [migrating, setMigrating] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const router = useRouter();
  const queryClient = useQueryClient();
  const remove = useMutation({
    mutationFn: () => api.deleteProject(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["projects"] });
      toast.success("Project removed");
      router.push("/projects");
    },
    onError: (e) => toast.error(e.message),
  });

  if (project.error) return <ErrorState error={project.error} />;
  if (!project.data) return <LoadingRows rows={6} />;
  const p = project.data;

  return (
    <>
      <PageHeader
        eyebrow={<Link href="/projects" className="hover:underline">Projects</Link>}
        title={p.name}
        description={<span className="font-mono text-xs">{p.path}</span>}
        actions={
          <>
            {auth.can("ADMIN") && (
              <Button variant="outline" onClick={() => setDeleting(true)} aria-label="Remove project">
                <Trash2 />
              </Button>
            )}
            {auth.can("MEMBER") && (
              <Button onClick={() => setMigrating(true)}>
                <Play /> Migrate
              </Button>
            )}
          </>
        }
      />
      <Tabs defaultValue="assessment">
        <TabsList>
          <TabsTrigger value="assessment">Assessment</TabsTrigger>
          <TabsTrigger value="migrations">Migrations {migrations.data ? `(${migrations.data.length})` : ""}</TabsTrigger>
        </TabsList>
        <TabsContent value="assessment" className="pt-4">
          {assessment.error ? (
            <ErrorState error={assessment.error} />
          ) : assessment.isPending ? (
            <LoadingRows rows={6} />
          ) : (
            <AssessmentView assessment={assessment.data} />
          )}
        </TabsContent>
        <TabsContent value="migrations" className="pt-4">
          {migrations.isPending ? (
            <LoadingRows />
          ) : migrations.data?.length ? (
            <MigrationTable migrations={migrations.data} showProject={false} />
          ) : (
            <Empty title="Not migrated yet">Review the assessment, then start a migration.</Empty>
          )}
        </TabsContent>
      </Tabs>
      <StartMigrationDialog project={p} open={migrating} onOpenChange={setMigrating} />
      <Dialog open={deleting} onOpenChange={setDeleting}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Remove {p.name}?</DialogTitle>
            <DialogDescription>
              Renova forgets the project. The project directory and existing migration workspaces are left as they are.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="destructive" onClick={() => remove.mutate()} disabled={remove.isPending}>
              Remove
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
