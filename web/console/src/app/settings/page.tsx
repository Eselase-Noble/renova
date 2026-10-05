"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { KeyRound, ShieldCheck } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";

import { ErrorState, LoadingRows, PageHeader } from "@/components/page";
import { ToneBadge } from "@/components/status";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { api, type ProviderSettings, type Settings } from "@/lib/api";

const EFFORTS: Record<string, string> = { default: "Provider default", low: "Low", medium: "Medium", high: "High", xhigh: "Extra high", max: "Max" };

export default function SettingsPage() {
  const settings = useQuery({ queryKey: ["settings"], queryFn: api.settings });
  return (
    <>
      <PageHeader
        title="Settings"
        description="AI runs on your own provider account. Renova never supplies, pools or shares keys."
      />
      {settings.error ? (
        <ErrorState error={settings.error} />
      ) : !settings.data ? (
        <LoadingRows rows={4} />
      ) : (
        <div className="space-y-6">
          <ProviderForm key={JSON.stringify([settings.data.provider, settings.data.model, settings.data.effort, settings.data.rag])} settings={settings.data} />
          <div className="grid gap-4 md:grid-cols-2">
            {settings.data.providers.map((p) => (
              <KeyCard key={p.name} provider={p} />
            ))}
          </div>
          <p className="text-xs text-muted-foreground">
            Stored in <span className="font-mono">{settings.data.configFile}</span> on the Renova server, readable only by the
            account it runs as. Environment variables on the server (such as ANTHROPIC_API_KEY) take precedence.
          </p>
        </div>
      )}
    </>
  );
}

function ProviderForm({ settings }: { settings: Settings }) {
  const [provider, setProvider] = useState(settings.provider);
  const [model, setModel] = useState(settings.model ?? "");
  const [effort, setEffort] = useState(settings.effort ?? "default");
  const [rag, setRag] = useState(settings.rag);
  const queryClient = useQueryClient();
  const providers: Record<string, string> = { none: "No AI (AI steps become manual work)" };
  settings.providers.forEach((p) => (providers[p.name] = p.displayName));
  const defaultModel = settings.providers.find((p) => p.name === provider)?.defaultModel;

  const save = useMutation({
    mutationFn: () =>
      api.updateSettings({
        "ai.provider": provider,
        "ai.model": model,
        "ai.effort": effort === "default" ? "" : effort,
        "rag.enabled": String(rag),
      }),
    onSuccess: (data) => {
      queryClient.setQueryData(["settings"], data);
      toast.success("Settings saved");
    },
    onError: (e) => toast.error(e.message),
  });
  const check = useMutation({
    mutationFn: api.checkSettings,
    onSuccess: (r) => toast.success(r.message),
    onError: (e) => toast.error(e.message),
  });

  return (
    <Card>
      <CardHeader>
        <CardTitle>AI provider</CardTitle>
        <CardDescription>Used for judgement calls, build repair and behaviour repair.</CardDescription>
      </CardHeader>
      <CardContent className="grid gap-4 md:grid-cols-2">
        <div className="space-y-2">
          <Label>Provider</Label>
          <Select value={provider} onValueChange={(v) => v && setProvider(v)} items={providers}>
            <SelectTrigger className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {Object.entries(providers).map(([value, label]) => (
                <SelectItem key={value} value={value}>
                  {label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="space-y-2">
          <Label htmlFor="model">Model</Label>
          <Input
            id="model"
            value={model}
            onChange={(e) => setModel(e.target.value)}
            placeholder={defaultModel ? `Default: ${defaultModel}` : "Provider default"}
            disabled={provider === "none"}
            className="font-mono"
          />
        </div>
        <div className="space-y-2">
          <Label>Effort</Label>
          <Select value={effort} onValueChange={(v) => v && setEffort(v)} items={EFFORTS} disabled={provider === "none"}>
            <SelectTrigger className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {Object.entries(EFFORTS).map(([value, label]) => (
                <SelectItem key={value} value={value}>
                  {label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="flex items-start justify-between gap-4 rounded-lg border p-3">
          <div>
            <Label htmlFor="rag">Retrieve context (RAG)</Label>
            <p className="text-xs text-muted-foreground">Related code, tests and migration notes in each request. No extra key.</p>
          </div>
          <Switch id="rag" checked={rag} onCheckedChange={(c) => setRag(c)} />
        </div>
      </CardContent>
      <CardFooter className="gap-2">
        <Button onClick={() => save.mutate()} disabled={save.isPending}>
          Save
        </Button>
        <Button variant="outline" onClick={() => check.mutate()} disabled={check.isPending || settings.provider === "none"}>
          <ShieldCheck /> {check.isPending ? "Checking…" : "Check key and model"}
        </Button>
      </CardFooter>
    </Card>
  );
}

function KeyCard({ provider }: { provider: ProviderSettings }) {
  const [key, setKey] = useState("");
  const queryClient = useQueryClient();
  const save = useMutation({
    mutationFn: () => api.setKey(provider.name, key),
    onSuccess: (data) => {
      setKey("");
      queryClient.setQueryData(["settings"], data);
      toast.success(`${provider.displayName} key saved`);
    },
    onError: (e) => toast.error(e.message),
  });
  const remove = useMutation({
    mutationFn: () => api.removeKey(provider.name),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["settings"] });
      toast.success("Key removed");
    },
    onError: (e) => toast.error(e.message),
  });
  const fromConfig = provider.keySource?.startsWith("user config");

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <KeyRound className="size-4 text-muted-foreground" />
          {provider.displayName}
        </CardTitle>
        <CardDescription className="flex flex-wrap items-center gap-2">
          {provider.keyConfigured ? (
            <>
              <ToneBadge tone="good">Key set</ToneBadge>
              <span className="font-mono">{provider.key}</span>
              <span>from {fromConfig ? "Renova settings" : provider.keySource}</span>
            </>
          ) : (
            <ToneBadge tone="muted">No key</ToneBadge>
          )}
        </CardDescription>
      </CardHeader>
      <CardContent>
        <form
          className="flex gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            save.mutate();
          }}
        >
          <Input
            type="password"
            autoComplete="off"
            placeholder={provider.keyConfigured ? "Replace the key" : "Paste your API key"}
            value={key}
            onChange={(e) => setKey(e.target.value)}
            aria-label={`${provider.displayName} API key`}
          />
          <Button type="submit" variant="secondary" disabled={!key || save.isPending}>
            Save
          </Button>
          {provider.keyConfigured && fromConfig && (
            <Button type="button" variant="ghost" onClick={() => remove.mutate()} disabled={remove.isPending}>
              Remove
            </Button>
          )}
        </form>
      </CardContent>
    </Card>
  );
}
