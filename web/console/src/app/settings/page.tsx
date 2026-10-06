"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { KeyRound, Lock, Monitor, Moon, ShieldCheck, Sun } from "lucide-react";
import { useTheme } from "next-themes";
import { useState } from "react";
import { toast } from "sonner";

import { ErrorState, Field, LoadingRows, PageHeader, Segmented } from "@/components/page";
import { ToneBadge } from "@/components/status";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { api, type ProviderSettings, type Settings } from "@/lib/api";
import { plural } from "@/lib/format";
import { useAuth } from "@/lib/auth";

const EFFORTS: Record<string, string> = { default: "Provider default", low: "Low", medium: "Medium", high: "High", xhigh: "Extra high", max: "Max" };

export default function SettingsPage() {
  const settings = useQuery({ queryKey: ["settings"], queryFn: api.settings });
  const auth = useAuth();
  const canEdit = auth.can("ADMIN");
  const organisation = auth.data?.organisation?.name ?? "your organisation";
  return (
    <>
      <PageHeader title="Settings" description={auth.local ? "How Renova works on this machine." : `How Renova works for ${organisation}.`} />
      {!canEdit && (
        <div className="mb-6 flex items-center gap-2.5 rounded-lg border bg-muted/40 px-4 py-2.5 text-sm text-muted-foreground">
          <Lock className="size-4 shrink-0" /> Only admins can change these settings.
        </div>
      )}
      {settings.error ? (
        <ErrorState error={settings.error} />
      ) : !settings.data ? (
        <LoadingRows rows={4} />
      ) : (
        <div className="divide-y [&>*]:py-8 [&>*:first-child]:pt-0 [&>*:last-child]:pb-0">
          <Section
            title="AI provider"
            description={`AI for ${organisation} runs on its own provider account. Renova never supplies, pools or shares keys. Without a provider, AI steps are listed for a person.`}
          >
            <ProviderForm
              key={JSON.stringify([settings.data.provider, settings.data.model, settings.data.effort, settings.data.rag])}
              settings={settings.data}
              canEdit={canEdit}
              canCheck={auth.can("MEMBER")}
            />
          </Section>
          <Section
            title="Provider keys"
            description="Keys are encrypted on the Renova server and only ever shown masked. Each organisation has its own. An endpoint points a provider at a gateway or an on-premises server."
          >
            <div className="space-y-4">
              {settings.data.providers.map((p) => (
                <KeyCard key={`${p.name}-${p.baseUrl}`} provider={p} canEdit={canEdit} active={settings.data.provider === p.name} />
              ))}
            </div>
          </Section>
          <Section title="Appearance" description="The theme for this browser. It is not shared with the rest of the organisation.">
            <Appearance />
          </Section>
          <Section title="Server" description="The Renova server this console talks to, and what is installed on it.">
            <Server />
          </Section>
        </div>
      )}
    </>
  );
}

/** A settings row: what it is on the left, the controls on the right. */
function Section({ title, description, children }: { title: string; description: string; children: React.ReactNode }) {
  return (
    <section className="grid gap-x-10 gap-y-4 lg:grid-cols-[18rem_minmax(0,1fr)]">
      <div>
        <h2 className="text-[15px] font-semibold tracking-tight">{title}</h2>
        <p className="mt-1 text-[13px] text-muted-foreground">{description}</p>
      </div>
      <div className="min-w-0">{children}</div>
    </section>
  );
}

function Appearance() {
  const { theme, setTheme } = useTheme();
  return (
    <Card>
      <CardContent className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <div className="text-sm font-medium">Theme</div>
          <p className="text-[13px] text-muted-foreground">System follows your device&apos;s light or dark setting.</p>
        </div>
        <Segmented
          label="Theme"
          value={theme ?? "system"}
          onChange={setTheme}
          options={[
            { value: "light", label: <><Sun /> Light</> },
            { value: "dark", label: <><Moon /> Dark</> },
            { value: "system", label: <><Monitor /> System</> },
          ]}
        />
      </CardContent>
    </Card>
  );
}

function Server() {
  const system = useQuery({ queryKey: ["system"], queryFn: api.system, staleTime: Infinity });
  if (system.error) return <ErrorState error={system.error} />;
  if (!system.data) return <LoadingRows rows={3} />;
  const info = system.data;
  return (
    <Card>
      <CardContent>
        <dl className="divide-y [&>*:first-child]:pt-0 [&>*:last-child]:pb-0">
          <Field label="Version"><span className="font-mono text-[13px]">{info.version}</span></Field>
          <Field label="Java"><span className="font-mono text-[13px]">{info.java}</span></Field>
          <Field label="Ecosystems">{info.ecosystems.map((e) => e.name).join(", ") || "None installed"}</Field>
          <Field label="Playbooks">{plural(info.playbooks, "playbook")} installed</Field>
          <Field label="AI providers"><span className="capitalize">{info.aiProviders.join(", ") || "None installed"}</span></Field>
          <Field label="Concurrency">{plural(info.parallelMigrations, "migration")} at a time; others wait in the queue</Field>
          <Field label="Mode">
            {info.localMode ? "Local: one person on this machine, no sign-in, answers only this machine" : "Server: accounts and organisations"}
          </Field>
          <Field label="Data folder">
            <span className="font-mono text-[13px] break-all">{info.dataDir}</span>
            <p className="mt-1 text-xs text-muted-foreground">Project records, settings and every migrated copy are kept here, on this {info.localMode ? "machine" : "server"}.</p>
          </Field>
          <Field label="Project folders">
            <ul className="space-y-0.5">
              {info.projectRoots.map((root) => (
                <li key={root} className="font-mono text-[13px] break-all">{root}</li>
              ))}
            </ul>
            <p className="mt-1 text-xs text-muted-foreground">Projects can only be added from these folders (renova.project-roots on the server).</p>
          </Field>
        </dl>
      </CardContent>
    </Card>
  );
}

function ProviderForm({ settings, canEdit, canCheck }: { settings: Settings; canEdit: boolean; canCheck: boolean }) {
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
      <CardContent className="grid gap-4 md:grid-cols-2">
        <div className="space-y-2">
          <Label>Provider</Label>
          <Select value={provider} onValueChange={(v) => v && setProvider(v)} items={providers} disabled={!canEdit}>
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
            disabled={provider === "none" || !canEdit}
            className="font-mono"
          />
        </div>
        <div className="space-y-2">
          <Label>Effort</Label>
          <Select value={effort} onValueChange={(v) => v && setEffort(v)} items={EFFORTS} disabled={provider === "none" || !canEdit}>
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
        <div className="flex items-start justify-between gap-4 rounded-lg border bg-muted/30 p-3">
          <div className="space-y-0.5">
            <Label htmlFor="rag">Retrieve context (RAG)</Label>
            <p className="text-xs text-muted-foreground">Related code, tests and migration notes in each request. No extra key.</p>
          </div>
          <Switch id="rag" checked={rag} onCheckedChange={(c) => setRag(c)} disabled={!canEdit} />
        </div>
      </CardContent>
      <CardFooter className="justify-between gap-2">
        <Button variant="outline" onClick={() => check.mutate()} disabled={check.isPending || settings.provider === "none" || !canCheck}>
          <ShieldCheck /> {check.isPending ? "Checking…" : "Check key and model"}
        </Button>
        <Button onClick={() => save.mutate()} disabled={save.isPending || !canEdit}>
          {save.isPending ? "Saving…" : "Save changes"}
        </Button>
      </CardFooter>
    </Card>
  );
}

function KeyCard({ provider, canEdit, active }: { provider: ProviderSettings; canEdit: boolean; active: boolean }) {
  const [key, setKey] = useState("");
  const [baseUrl, setBaseUrl] = useState(provider.baseUrl ?? "");
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
  const saveUrl = useMutation({
    mutationFn: () => api.updateSettings({ [`${provider.name}.baseUrl`]: baseUrl }),
    onSuccess: (data) => {
      queryClient.setQueryData(["settings"], data);
      toast.success("Endpoint saved");
    },
    onError: (e) => toast.error(e.message),
  });

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <KeyRound className="size-4 text-muted-foreground" />
          {provider.displayName}
          {active && <ToneBadge tone="brand">In use</ToneBadge>}
        </CardTitle>
        <CardDescription className="flex flex-wrap items-center gap-2">
          {provider.keyConfigured ? (
            <>
              <ToneBadge tone="good">Key set</ToneBadge>
              <span className="font-mono text-xs">{provider.key}</span>
            </>
          ) : (
            <ToneBadge tone="muted">No key</ToneBadge>
          )}
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        <form className="flex gap-2" onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
          <Input
            type="password"
            autoComplete="off"
            placeholder={provider.keyConfigured ? "Replace the key" : "Paste your API key"}
            value={key}
            onChange={(e) => setKey(e.target.value)}
            aria-label={`${provider.displayName} API key`}
            disabled={!canEdit}
          />
          <Button type="submit" variant="secondary" disabled={!key || save.isPending || !canEdit}>
            Save
          </Button>
          {provider.keyConfigured && canEdit && (
            <Button type="button" variant="ghost" onClick={() => remove.mutate()} disabled={remove.isPending}>
              Remove
            </Button>
          )}
        </form>
        <form className="flex gap-2" onSubmit={(e) => { e.preventDefault(); saveUrl.mutate(); }}>
          <Input
            placeholder="Endpoint (optional), e.g. an on-premises server"
            value={baseUrl}
            onChange={(e) => setBaseUrl(e.target.value)}
            className="font-mono text-xs"
            aria-label={`${provider.displayName} endpoint`}
            disabled={!canEdit}
          />
          <Button type="submit" variant="ghost" disabled={saveUrl.isPending || !canEdit || baseUrl === (provider.baseUrl ?? "")}>
            Save
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}
