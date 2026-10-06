"use client";

import { Check, Copy, Search, TriangleAlert, type LucideIcon } from "lucide-react";
import { useState } from "react";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Skeleton } from "@/components/ui/skeleton";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { initials } from "@/lib/format";
import { cn } from "@/lib/utils";

export function PageHeader({
  title,
  description,
  actions,
  icon: Icon,
  meta,
}: {
  title: React.ReactNode;
  description?: React.ReactNode;
  actions?: React.ReactNode;
  /** A tile beside the title, for pages about one thing (a project, a migration). */
  icon?: LucideIcon;
  /** Chips under the title: status, ecosystem, dates. */
  meta?: React.ReactNode;
}) {
  return (
    <div className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
      <div className="flex min-w-0 items-start gap-3.5">
        {Icon && (
          <span className="mt-0.5 grid size-10 shrink-0 place-items-center rounded-lg border bg-card text-brand shadow-(--shadow-card)">
            <Icon className="size-5" />
          </span>
        )}
        <div className="min-w-0 space-y-1">
          <h1 className="truncate text-[22px] leading-7 font-semibold tracking-tight">{title}</h1>
          {description && <div className="max-w-3xl text-sm text-muted-foreground">{description}</div>}
          {meta && <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5 pt-1.5 text-xs text-muted-foreground">{meta}</div>}
        </div>
      </div>
      {actions && <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}

/** A card with a titled header row; the body is not padded so tables and lists can run edge to edge. */
export function Panel({
  title,
  description,
  actions,
  children,
  className,
  bodyClassName,
}: {
  title?: React.ReactNode;
  description?: React.ReactNode;
  actions?: React.ReactNode;
  children: React.ReactNode;
  className?: string;
  bodyClassName?: string;
}) {
  return (
    <section className={cn("flex flex-col overflow-hidden rounded-xl border bg-card shadow-(--shadow-card)", className)}>
      {(title || actions) && (
        <header className="flex min-h-13 flex-wrap items-center justify-between gap-x-4 gap-y-2 border-b px-5 py-3">
          <div className="min-w-0">
            <h2 className="text-[15px] leading-5 font-semibold tracking-tight">{title}</h2>
            {description && <p className="mt-0.5 text-[13px] text-muted-foreground">{description}</p>}
          </div>
          {actions && <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div>}
        </header>
      )}
      <div className={cn("min-w-0 flex-1 p-5", bodyClassName)}>{children}</div>
    </section>
  );
}

/** One headline number. `children` holds an optional meter or sparkline under the value. */
export function Stat({
  label,
  value,
  hint,
  icon: Icon,
  children,
}: {
  label: string;
  value: React.ReactNode;
  hint?: React.ReactNode;
  icon?: LucideIcon;
  children?: React.ReactNode;
}) {
  return (
    <div className="flex flex-col gap-1.5 rounded-xl border bg-card p-4 shadow-(--shadow-card)">
      <div className="flex items-center justify-between gap-2 text-[13px] text-muted-foreground">
        <span>{label}</span>
        {Icon && <Icon className="size-4 opacity-70" />}
      </div>
      <div className="text-2xl leading-8 font-semibold tracking-tight">{value}</div>
      {children}
      {hint && <div className="text-xs text-muted-foreground">{hint}</div>}
    </div>
  );
}

/** A thin bar for a share of a whole; the track is a lighter step of the fill. */
export function Meter({ value, tone = "brand", label }: { value: number; tone?: "brand" | "success" | "warning" | "danger"; label: string }) {
  const fill = { brand: "bg-brand", success: "bg-success", warning: "bg-warning", danger: "bg-danger" }[tone];
  const track = { brand: "bg-brand/15", success: "bg-success/15", warning: "bg-warning/15", danger: "bg-danger/15" }[tone];
  const pct = Math.max(0, Math.min(1, value)) * 100;
  return (
    <div role="meter" aria-label={label} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(pct)} className={cn("h-1.5 overflow-hidden rounded-full", track)}>
      <div className={cn("h-full rounded-full transition-[width] duration-500", fill)} style={{ width: `${pct}%` }} />
    </div>
  );
}

export function ErrorState({ error }: { error: unknown }) {
  return (
    <Alert variant="destructive">
      <TriangleAlert />
      <AlertTitle>Could not load this page</AlertTitle>
      <AlertDescription>
        {error instanceof Error ? error.message : String(error)}. Check that the Renova API is running (web/api).
      </AlertDescription>
    </Alert>
  );
}

export function LoadingRows({ rows = 3 }: { rows?: number }) {
  return (
    <div className="space-y-2">
      {Array.from({ length: rows }, (_, i) => (
        <Skeleton key={i} className="h-10 w-full" />
      ))}
    </div>
  );
}

export function Empty({
  title,
  children,
  icon: Icon,
  action,
  className,
}: {
  title: string;
  children?: React.ReactNode;
  icon?: LucideIcon;
  action?: React.ReactNode;
  className?: string;
}) {
  return (
    <div className={cn("flex flex-col items-center rounded-xl border border-dashed px-6 py-10 text-center", className)}>
      {Icon && (
        <span className="mb-3 grid size-10 place-items-center rounded-full bg-muted text-muted-foreground">
          <Icon className="size-5" />
        </span>
      )}
      <div className="text-sm font-medium">{title}</div>
      {children && <div className="mt-1 max-w-md text-[13px] text-muted-foreground">{children}</div>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}

export function SearchInput({
  value,
  onChange,
  placeholder,
  className,
}: {
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
  className?: string;
}) {
  return (
    <label className={cn("relative block", className)}>
      <Search className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground" />
      <input
        type="search"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        aria-label={placeholder}
        className="h-8 w-full rounded-lg border border-input bg-card pr-2.5 pl-8 text-sm outline-none placeholder:text-muted-foreground focus-visible:border-brand focus-visible:ring-3 focus-visible:ring-ring/40 dark:bg-input/30 [&::-webkit-search-cancel-button]:hidden"
      />
    </label>
  );
}

/** A compact one-of-many choice: status filters, list or grid. */
export function Segmented<T extends string>({
  value,
  onChange,
  options,
  label,
}: {
  value: T;
  onChange: (value: T) => void;
  options: { value: T; label: React.ReactNode; count?: number; title?: string }[];
  label: string;
}) {
  return (
    <div role="radiogroup" aria-label={label} className="inline-flex h-8 items-center rounded-lg border bg-muted/50 p-0.5">
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          role="radio"
          aria-checked={o.value === value}
          title={o.title}
          onClick={() => onChange(o.value)}
          className={cn(
            "inline-flex h-full items-center gap-1.5 rounded-md px-2.5 text-[13px] font-medium whitespace-nowrap text-muted-foreground transition-colors outline-none hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring/60 [&>svg]:size-4",
            o.value === value && "bg-card text-foreground shadow-sm dark:bg-accent",
          )}
        >
          {o.label}
          {o.count !== undefined && <span className="text-xs font-normal text-muted-foreground tabular-nums">{o.count}</span>}
        </button>
      ))}
    </div>
  );
}

export function CopyButton({ value, label = "Copy", className }: { value: string; label?: string; className?: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <Tooltip>
      <TooltipTrigger
        render={
          <button
            type="button"
            aria-label={label}
            onClick={async (e) => {
              e.stopPropagation();
              await navigator.clipboard.writeText(value);
              setCopied(true);
              setTimeout(() => setCopied(false), 1500);
            }}
            className={cn(
              "inline-grid size-6 shrink-0 place-items-center rounded-md text-muted-foreground outline-none hover:bg-muted hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring/60",
              className,
            )}
          />
        }
      >
        {copied ? <Check className="size-3.5 text-success" /> : <Copy className="size-3.5" />}
      </TooltipTrigger>
      <TooltipContent>{copied ? "Copied" : label}</TooltipContent>
    </Tooltip>
  );
}

const AVATAR_HUES = [268, 200, 155, 30, 330, 95];

/** Initials on a tint picked from the name, so the same person always has the same colour. */
export function Avatar({ name, className }: { name: string; className?: string }) {
  const hue = AVATAR_HUES[[...name].reduce((n, c) => n + c.charCodeAt(0), 0) % AVATAR_HUES.length];
  return (
    <span
      aria-hidden
      className={cn("grid size-7 shrink-0 place-items-center rounded-full text-[11px] font-semibold", className)}
      style={{ background: `oklch(0.6 0.14 ${hue} / 18%)`, color: `color-mix(in oklch, oklch(0.6 0.16 ${hue}), var(--foreground) 35%)` }}
    >
      {initials(name) || "?"}
    </span>
  );
}

/** A label and value pair for detail lists. */
export function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="grid grid-cols-[9rem_1fr] items-baseline gap-3 py-2 text-sm">
      <dt className="text-[13px] text-muted-foreground">{label}</dt>
      <dd className="min-w-0 break-words">{children}</dd>
    </div>
  );
}
