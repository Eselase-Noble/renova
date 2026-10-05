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

export function NewOrganisationDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const [name, setName] = useState("");
  const queryClient = useQueryClient();
  const router = useRouter();
  const create = useMutation({
    mutationFn: () => api.createOrganisation(name),
    onSuccess: async (org) => {
      queryClient.clear();
      await queryClient.fetchQuery({ queryKey: ["auth"], queryFn: api.authState });
      onOpenChange(false);
      setName("");
      toast.success(`Created ${org.name}`);
      router.push("/settings");
    },
    onError: (e) => toast.error(e.message),
  });
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <form className="space-y-4" onSubmit={(e) => { e.preventDefault(); create.mutate(); }}>
          <DialogHeader>
            <DialogTitle>New organisation</DialogTitle>
            <DialogDescription>
              Organisations keep their projects, migrations and AI keys apart. You will be its owner.
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-2">
            <Label htmlFor="org-name">Name</Label>
            <Input id="org-name" required autoFocus value={name} onChange={(e) => setName(e.target.value)} />
          </div>
          <DialogFooter>
            <Button type="submit" disabled={!name || create.isPending}>Create</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

export function ChangePasswordDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const [current, setCurrent] = useState("");
  const [replacement, setReplacement] = useState("");
  const change = useMutation({
    mutationFn: () => api.changePassword(current, replacement),
    onSuccess: () => {
      toast.success("Password changed");
      setCurrent("");
      setReplacement("");
      onOpenChange(false);
    },
    onError: (e) => toast.error(e.message),
  });
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <form className="space-y-4" onSubmit={(e) => { e.preventDefault(); change.mutate(); }}>
          <DialogHeader>
            <DialogTitle>Change password</DialogTitle>
          </DialogHeader>
          <div className="space-y-2">
            <Label htmlFor="current">Current password</Label>
            <Input id="current" type="password" autoComplete="current-password" required value={current} onChange={(e) => setCurrent(e.target.value)} />
          </div>
          <div className="space-y-2">
            <Label htmlFor="replacement">New password</Label>
            <Input id="replacement" type="password" autoComplete="new-password" minLength={10} required value={replacement} onChange={(e) => setReplacement(e.target.value)} />
            <p className="text-xs text-muted-foreground">At least 10 characters.</p>
          </div>
          <DialogFooter>
            <Button type="submit" disabled={change.isPending}>Change password</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
