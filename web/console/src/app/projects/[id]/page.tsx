"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { BookOpenCheck, CalendarDays, FolderGit2, MoreHorizontal, Play, Target, Trash2, Workflow } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { use, useState } from "react";
import { toast } from "sonner";

import { AssessmentView, FindingsView, PlanView } from "@/components/assessment";
import { MigrationTable } from "@/components/migration-table";
import { CopyButton, Empty, ErrorState, LoadingRows, PageHeader, Panel } from "@/components/page";
import { StartMigrationDialog } from "@/components/start-migration-dialog";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { active, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { dateTime } from "@/lib/format";

export default function ProjectPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const project = useQuery({ queryKey: ["project", id], queryFn: () => api.project(id) });
  const assessment = useQuery({ queryKey: ["assessment", id], queryFn: () => api.assessment(id), staleTime: 60_000 });
  const migrations = useQuery({
    queryKey: ["migrations", "project", id],
    queryFn: () => api.projectMigrations(id),
    refetchInterval: (q) => (q.state.data?.some((m) => active(m.status)) ? 3000 : false),
  });
  const targets = useQuery({ queryKey: ["targets", id], queryFn: () => api.projectTargets(id), staleTime: 60_000 });
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

  const retarget = useMutation({
    mutationFn: (playbook: string) => api.retargetProject(id, playbook),
    onSuccess: (changed) => {
      queryClient.setQueryData(["project", id], changed);
      queryClient.invalidateQueries({ queryKey: ["targets", id] });
      queryClient.invalidateQueries({ queryKey: ["assessment", id] });
      queryClient.invalidateQueries({ queryKey: ["projects"] });
      toast.success("Target changed; the assessment is being redone");
    },
    onError: (e) => toast.error(e.message),
  });

  if (project.error) return <ErrorState error={project.error} />;
  if (!project.data) return <LoadingRows rows={6} />;
  const p = project.data;
  const assessed = (render: (a: NonNullable<typeof assessment.data>) => React.ReactNode) =>
    assessment.error ? <ErrorState error={assessment.error} /> : assessment.isPending ? <LoadingRows rows={6} /> : render(assessment.data);

  return (
    <>
      <PageHeader
        icon={FolderGit2}
        title={p.name}
        description={
          <span className="flex items-center gap-1">
            <span className="truncate font-mono text-xs">{p.path}</span>
            <CopyButton value={p.path} label="Copy path" />
          </span>
        }
        meta={
          <>
            <span className="inline-flex items-center gap-1.5 capitalize">
              <Workflow className="size-3.5" /> {p.ecosystem}
            </span>
            <Link href="/playbooks" className="inline-flex items-center gap-1.5 font-mono hover:text-foreground hover:underline">
              <BookOpenCheck className="size-3.5" /> {p.playbook}
            </Link>
            <span className="inline-flex items-center gap-1.5">
              <CalendarDays className="size-3.5" /> Added {dateTime(p.createdAt)}
            </span>
          </>
        }
        actions={
          <>
            {auth.can("ADMIN") && (
              <DropdownMenu>
                <DropdownMenuTrigger render={<Button variant="outline" size="icon" aria-label="More actions" />}>
                  <MoreHorizontal />
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" className="w-48">
                  <DropdownMenuItem variant="destructive" onClick={() => setDeleting(true)}>
                    <Trash2 /> Remove project
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            )}
            {auth.can("MEMBER") && (
              <Button onClick={() => setMigrating(true)}>
                <Play /> Migrate
              </Button>
            )}
          </>
        }
      />
      {targets.data && targets.data.playbooks.length > 1 && (
        <div className="mb-6 flex flex-wrap items-center gap-x-4 gap-y-2 rounded-xl border bg-card px-5 py-3 shadow-(--shadow-card)">
          <Target className="size-4 shrink-0 text-brand" />
          <div className="min-w-0 flex-1">
            <div className="text-sm font-medium">Target</div>
            <p className="text-[13px] text-muted-foreground">
              What this project is migrated to. The assessment, the plan and new migrations follow it.
              {targets.data.current !== targets.data.recommended && " Renova suggests another target for this project."}
            </p>
          </div>
          <Select
            value={targets.data.current}
            onValueChange={(v) => v && v !== targets.data.current && retarget.mutate(v)}
            items={Object.fromEntries(targets.data.playbooks.map((t) => [t.id, t.name]))}
            disabled={!auth.can("ADMIN") || retarget.isPending}
          >
            <SelectTrigger className="w-full sm:w-96" aria-label="Target">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {targets.data.playbooks.map((t) => (
                <SelectItem key={t.id} value={t.id}>
                  {t.name}
                  {t.id === targets.data.recommended && <span className="ml-2 text-xs text-muted-foreground">suggested</span>}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      )}
      <Tabs defaultValue="assessment">
        <TabsList variant="line" className="w-full justify-start border-b">
          <TabsTrigger value="assessment" className="flex-none px-3">Assessment</TabsTrigger>
          <TabsTrigger value="plan" className="flex-none px-3">
            Plan {assessment.data && <Count n={assessment.data.plan.length} />}
          </TabsTrigger>
          <TabsTrigger value="findings" className="flex-none px-3">
            Findings {assessment.data && <Count n={assessment.data.findings.length} />}
          </TabsTrigger>
          <TabsTrigger value="migrations" className="flex-none px-3">
            Migrations {migrations.data && <Count n={migrations.data.length} />}
          </TabsTrigger>
        </TabsList>
        <TabsContent value="assessment" className="pt-5">
          {assessed((a) => <AssessmentView assessment={a} />)}
        </TabsContent>
        <TabsContent value="plan" className="pt-5">
          {assessed((a) => <PlanView plan={a.plan} />)}
        </TabsContent>
        <TabsContent value="findings" className="pt-5">
          {assessed((a) => <FindingsView assessment={a} />)}
        </TabsContent>
        <TabsContent value="migrations" className="pt-5">
          {migrations.isPending ? (
            <LoadingRows />
          ) : migrations.data?.length ? (
            <Panel bodyClassName="p-0">
              <MigrationTable migrations={migrations.data} showProject={false} />
            </Panel>
          ) : (
            <Empty
              title="Not migrated yet"
              icon={Workflow}
              action={
                auth.can("MEMBER") && (
                  <Button onClick={() => setMigrating(true)}>
                    <Play /> Migrate
                  </Button>
                )
              }
            >
              Review the assessment and the plan, then start a migration. It works on a copy.
            </Empty>
          )}
        </TabsContent>
      </Tabs>
      <StartMigrationDialog project={p} open={migrating} onOpenChange={setMigrating} />
      <Dialog open={deleting} onOpenChange={setDeleting}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Remove {p.name}?</DialogTitle>
            <DialogDescription>
              Renova forgets the project. The project folder and existing migration workspaces are left as they are.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDeleting(false)}>
              Keep it
            </Button>
            <Button variant="destructive" onClick={() => remove.mutate()} disabled={remove.isPending}>
              Remove project
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}

function Count({ n }: { n: number }) {
  return <span className="rounded-full bg-muted px-1.5 text-[11px] leading-[18px] font-medium text-muted-foreground tabular-nums">{n}</span>;
}
