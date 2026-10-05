"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { api } from "@/lib/api";

export function AddProjectDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const [path, setPath] = useState("");
  const [name, setName] = useState("");
  const router = useRouter();
  const queryClient = useQueryClient();
  const add = useMutation({
    mutationFn: () => api.addProject({ path, name: name || undefined }),
    onSuccess: (project) => {
      queryClient.invalidateQueries({ queryKey: ["projects"] });
      toast.success(`Added ${project.name}`);
      onOpenChange(false);
      router.push(`/projects/${project.id}`);
    },
    onError: (e) => toast.error(e.message),
  });

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
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
              A directory on the Renova server that holds the project. Renova reads it and migrates a copy; the
              original is never changed.
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-2">
            <Label htmlFor="path">Directory</Label>
            <Input
              id="path"
              required
              autoFocus
              placeholder="/srv/projects/billing-app"
              value={path}
              onChange={(e) => setPath(e.target.value)}
              className="font-mono"
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="name">Name (optional)</Label>
            <Input id="name" placeholder="Defaults to the directory name" value={name} onChange={(e) => setName(e.target.value)} />
          </div>
          <DialogFooter>
            <Button type="submit" disabled={!path || add.isPending}>
              {add.isPending ? "Adding…" : "Add project"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
