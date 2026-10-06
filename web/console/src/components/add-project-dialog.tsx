"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronRight, CornerLeftUp, Folder, FolderGit2, HardDrive } from "lucide-react";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { toast } from "sonner";

import { ToneBadge } from "@/components/status";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { api } from "@/lib/api";
import { cn } from "@/lib/utils";

export function AddProjectDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-xl">{open && <AddProjectForm onDone={() => onOpenChange(false)} />}</DialogContent>
    </Dialog>
  );
}

function AddProjectForm({ onDone }: { onDone: () => void }) {
  const [path, setPath] = useState("");
  const [name, setName] = useState("");
  /** The folder whose contents are listed; null lists the folders the server allows. */
  const [browsing, setBrowsing] = useState<string | null>(null);
  const router = useRouter();
  const queryClient = useQueryClient();
  const listing = useQuery({ queryKey: ["directories", browsing], queryFn: () => api.directories(browsing), retry: false });
  const add = useMutation({
    mutationFn: () => api.addProject({ path, name: name || undefined }),
    onSuccess: (project) => {
      queryClient.invalidateQueries({ queryKey: ["projects"] });
      toast.success(`Added ${project.name}`);
      onDone();
      router.push(`/projects/${project.id}`);
    },
    onError: (e) => toast.error(e.message),
  });
  const open = (folder: string | null) => {
    setBrowsing(folder);
    if (folder) setPath(folder);
  };

  return (
    <form
      className="space-y-4"
      onSubmit={(e) => {
        e.preventDefault();
        add.mutate();
      }}
    >
      <DialogHeader>
        <DialogTitle>Add a project</DialogTitle>
        <DialogDescription>
          Choose the folder on the Renova server that holds the project. Renova reads it and migrates a copy; the original is never changed.
        </DialogDescription>
      </DialogHeader>
      <div className="space-y-2">
        <Label htmlFor="path">Folder</Label>
        <Input
          id="path"
          required
          placeholder="/srv/projects/billing-app"
          value={path}
          onChange={(e) => setPath(e.target.value)}
          onKeyDown={(e) => {
            // Enter in the path box opens that folder in the list rather than submitting half a path.
            if (e.key === "Enter" && path && path !== browsing) {
              e.preventDefault();
              setBrowsing(path);
            }
          }}
          className="font-mono"
        />
        <div className="overflow-hidden rounded-lg border">
          <div className="flex items-center gap-2 border-b bg-muted/40 px-3 py-1.5 text-xs text-muted-foreground">
            <HardDrive className="size-3.5 shrink-0" />
            <span className={cn("min-w-0 flex-1 truncate", listing.data?.path && "font-mono")}>{listing.data?.path ?? "Folders this server allows"}</span>
            {listing.data?.path && (
              <button
                type="button"
                onClick={() => open(listing.data.parent)}
                className="inline-flex shrink-0 items-center gap-1 rounded px-1.5 py-0.5 outline-none hover:bg-muted hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring"
              >
                <CornerLeftUp className="size-3.5" /> Up
              </button>
            )}
          </div>
          <ul className="scroll-thin h-52 overflow-y-auto p-1 text-sm" aria-label="Folders">
            {listing.isPending && <li className="px-2 py-2 text-[13px] text-muted-foreground">Reading folders…</li>}
            {listing.error && <li className="px-2 py-2 text-[13px] text-danger">{listing.error.message}</li>}
            {listing.data?.entries.length === 0 && <li className="px-2 py-2 text-[13px] text-muted-foreground">No folders inside this one.</li>}
            {listing.data?.entries.map((entry) => (
              <li key={entry.path} className={cn("flex items-center rounded-md hover:bg-muted", path === entry.path && "bg-brand/10 hover:bg-brand/10")}>
                <button
                  type="button"
                  onClick={() => setPath(entry.path)}
                  onDoubleClick={() => open(entry.path)}
                  aria-pressed={path === entry.path}
                  className="flex min-w-0 flex-1 items-center gap-2 rounded-md px-2 py-1.5 text-left outline-none focus-visible:ring-2 focus-visible:ring-ring"
                >
                  {entry.project ? <FolderGit2 className="size-4 shrink-0 text-brand" /> : <Folder className="size-4 shrink-0 text-muted-foreground" />}
                  <span className="min-w-0 flex-1 truncate">{entry.name}</span>
                  {entry.project && <ToneBadge tone="brand">Project</ToneBadge>}
                </button>
                <button
                  type="button"
                  aria-label={`Look inside ${entry.name}`}
                  onClick={() => open(entry.path)}
                  className="mr-1 grid size-6 shrink-0 place-items-center rounded text-muted-foreground outline-none hover:bg-background hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring"
                >
                  <ChevronRight className="size-4" />
                </button>
              </li>
            ))}
            {listing.data?.truncated && <li className="px-2 py-2 text-xs text-muted-foreground">More folders than can be listed; type the path above.</li>}
          </ul>
        </div>
        <p className="text-xs text-muted-foreground">Click a folder to choose it, or the arrow to look inside. Folders with a build file are marked as projects.</p>
      </div>
      <div className="space-y-2">
        <Label htmlFor="name">Name (optional)</Label>
        <Input id="name" placeholder="Defaults to the folder name" value={name} onChange={(e) => setName(e.target.value)} />
      </div>
      <DialogFooter>
        <Button type="button" variant="outline" onClick={onDone}>
          Cancel
        </Button>
        <Button type="submit" disabled={!path || add.isPending}>
          {add.isPending ? "Adding…" : "Add project"}
        </Button>
      </DialogFooter>
    </form>
  );
}
