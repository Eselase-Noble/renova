import { GitCommitVertical, KeyRound, ShieldCheck } from "lucide-react";

import { LogoMark, Wordmark } from "@/components/brand";

const POINTS = [
  { icon: ShieldCheck, title: "Your source is never changed", text: "Renova migrates a copy. The original project is only read." },
  { icon: GitCommitVertical, title: "Every stage is a commit", text: "Review, audit or revert each step of a migration on its own." },
  { icon: KeyRound, title: "Your own AI key", text: "AI runs on your organisation's provider account, never a shared one." },
];

/** The frame for pages outside the app: the form on one side, what Renova is on the other. */
export function AuthCard({ title, description, children }: { title: string; description?: React.ReactNode; children: React.ReactNode }) {
  return (
    <div className="grid min-h-svh lg:grid-cols-[minmax(0,5fr)_minmax(0,6fr)]">
      {/* The brand panel is deep navy in both themes, whatever the sidebar's colours are. */}
      <aside
        className="relative hidden flex-col justify-between overflow-hidden bg-sidebar p-10 text-sidebar-foreground lg:flex"
        style={
          {
            "--sidebar": "oklch(0.205 0.035 268)",
            "--sidebar-foreground": "oklch(0.86 0.015 268)",
            "--sidebar-primary": "oklch(0.62 0.19 268)",
            "--sidebar-border": "oklch(1 0 0 / 9%)",
          } as React.CSSProperties
        }
      >
        <div aria-hidden className="bg-grid absolute inset-0 [mask-image:radial-gradient(ellipse_at_top_left,black,transparent_75%)]" />
        <div aria-hidden className="absolute -top-40 -left-40 size-[32rem] rounded-full bg-sidebar-primary/25 blur-3xl" />
        <div className="relative flex items-center gap-2.5">
          <LogoMark />
          <Wordmark className="text-lg text-white" />
        </div>
        <div className="relative max-w-md space-y-8">
          <h2 className="text-3xl leading-tight font-semibold tracking-tight text-white">Modernise legacy systems, safely and repeatably.</h2>
          <ul className="space-y-5">
            {POINTS.map(({ icon: Icon, title: heading, text }) => (
              <li key={heading} className="flex gap-3.5">
                <span className="grid size-9 shrink-0 place-items-center rounded-lg border border-sidebar-border bg-white/5 text-white">
                  <Icon className="size-4" />
                </span>
                <div>
                  <div className="text-sm font-medium text-white">{heading}</div>
                  <p className="text-sm text-sidebar-foreground/75">{text}</p>
                </div>
              </li>
            ))}
          </ul>
        </div>
        <p className="relative text-xs text-sidebar-foreground/50">Runs on your own server. Assess, migrate and verify without your code leaving it.</p>
      </aside>
      <main className="flex items-center justify-center px-4 py-10">
        <div className="w-full max-w-sm">
          <div className="mb-8 flex items-center gap-2.5 lg:hidden">
            <LogoMark />
            <Wordmark className="text-lg" />
          </div>
          <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
          {description && <p className="mt-1.5 text-sm text-muted-foreground">{description}</p>}
          <div className="mt-7">{children}</div>
        </div>
      </main>
    </div>
  );
}

export function Field({ id, label, hint, ...input }: React.ComponentProps<"input"> & { id: string; label: string; hint?: string }) {
  return (
    <div className="space-y-1.5">
      <label htmlFor={id} className="text-sm font-medium">{label}</label>
      <input
        id={id}
        className="h-9 w-full rounded-lg border border-input bg-card px-3 text-sm shadow-(--shadow-card) outline-none placeholder:text-muted-foreground focus-visible:border-brand focus-visible:ring-3 focus-visible:ring-ring/40 dark:bg-input/30"
        {...input}
      />
      {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
    </div>
  );
}
