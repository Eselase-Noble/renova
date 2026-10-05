"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { api, type Project } from "@/lib/api";

const ROUNDS: Record<string, string> = { "0": "No repair", "1": "1 round", "2": "2 rounds", "3": "3 rounds", "5": "5 rounds" };

export function StartMigrationDialog({
  project,
  open,
  onOpenChange,
}: {
  project: Project;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const settings = useQuery({ queryKey: ["settings"], queryFn: api.settings, enabled: open });
  const aiReady =
    !!settings.data && settings.data.provider !== "none" &&
    !!settings.data.providers.find((p) => p.name === settings.data.provider)?.keyConfigured;
  const [ai, setAi] = useState(true);
  const [rag, setRag] = useState(true);
  const [behaviour, setBehaviour] = useState(true);
  const [tests, setTests] = useState(true);
  const [rounds, setRounds] = useState("3");
  const router = useRouter();
  const queryClient = useQueryClient();

  const start = useMutation({
    mutationFn: () =>
      api.startMigration(project.id, {
        ai: ai && aiReady,
        rag: ai && aiReady && rag,
        verifyBehaviour: behaviour,
        skipTests: !tests,
        maxAiIterations: Number(rounds),
      }),
    onSuccess: (m) => {
      queryClient.invalidateQueries({ queryKey: ["migrations"] });
      toast.success("Migration started");
      router.push(`/migrations/${m.id}`);
    },
    onError: (e) => toast.error(e.message),
  });

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Migrate {project.name}</DialogTitle>
          <DialogDescription>
            Renova migrates a copy into its own workspace. Each stage is a commit you can review.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <Option
            id="ai"
            label="Use AI"
            description={
              aiReady ? (
                <>For judgement calls and build repair, with {settings.data?.provider} on your own key.</>
              ) : (
                <>
                  Your organisation has no AI provider with a key yet. An admin can{" "}
                  <Link className="underline" href="/settings">add one in Settings</Link>, or migrate without AI: those
                  steps are then listed for a person.
                </>
              )
            }
            checked={ai && aiReady}
            disabled={!aiReady}
            onChange={setAi}
          />
          <Option
            id="rag"
            label="Retrieve context (RAG)"
            description="Related project code, tests and migration notes in each AI request. No extra key."
            checked={ai && aiReady && rag}
            disabled={!(ai && aiReady)}
            onChange={setRag}
          />
          <Option
            id="tests"
            label="Run the project's tests"
            description="Code that compiles can still fail at runtime; the tests catch it."
            checked={tests}
            onChange={setTests}
          />
          <Option
            id="behaviour"
            label="Verify behaviour"
            description="Run the original and the migrated app side by side in Docker and compare their answers."
            checked={behaviour}
            onChange={setBehaviour}
          />
          <div className="flex items-center justify-between gap-4">
            <div>
              <Label>AI repair rounds</Label>
              <p className="text-xs text-muted-foreground">For build errors and behaviour differences.</p>
            </div>
            <Select value={rounds} onValueChange={(v) => v && setRounds(v)} items={ROUNDS} disabled={!(ai && aiReady)}>
              <SelectTrigger className="w-32">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {Object.entries(ROUNDS).map(([value, label]) => (
                  <SelectItem key={value} value={value}>
                    {label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        </div>
        <DialogFooter>
          <Button onClick={() => start.mutate()} disabled={start.isPending}>
            {start.isPending ? "Starting…" : "Start migration"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function Option({
  id,
  label,
  description,
  checked,
  disabled,
  onChange,
}: {
  id: string;
  label: string;
  description: React.ReactNode;
  checked: boolean;
  disabled?: boolean;
  onChange: (checked: boolean) => void;
}) {
  return (
    <div className="flex items-start justify-between gap-4">
      <div>
        <Label htmlFor={id}>{label}</Label>
        <p className="text-xs text-muted-foreground">{description}</p>
      </div>
      <Switch id={id} checked={checked} disabled={disabled} onCheckedChange={(c) => onChange(c)} />
    </div>
  );
}
