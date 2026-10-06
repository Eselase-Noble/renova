"use client";

import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";

// Chart marks take their colour from the --series-* (identity) and --status-* (outcome) tokens in globals.css.
// Labels and values always use text colours; a swatch beside them carries the identity.

export interface Segment {
  key: string;
  label: string;
  value: number;
  /** A CSS colour, normally var(--series-N) or var(--status-…). */
  color: string;
}

export function Legend({ items, className }: { items: Segment[]; className?: string }) {
  const total = items.reduce((n, s) => n + s.value, 0);
  return (
    <ul className={cn("flex flex-wrap gap-x-5 gap-y-1.5 text-[13px]", className)}>
      {items.map((s) => (
        <li key={s.key} className="flex items-center gap-2">
          <span aria-hidden className="size-2.5 shrink-0 rounded-[3px]" style={{ background: s.color }} />
          <span className="text-muted-foreground">{s.label}</span>
          <span className="font-medium tabular-nums">{s.value}</span>
          {total > 0 && <span className="text-xs text-muted-foreground tabular-nums">{Math.round((s.value / total) * 100)}%</span>}
        </li>
      ))}
    </ul>
  );
}

/** One bar split into parts of a whole, with the legend that names them. */
export function StackedBar({ segments, label, unit }: { segments: Segment[]; label: string; unit: string }) {
  const total = segments.reduce((n, s) => n + s.value, 0);
  const shown = segments.filter((s) => s.value > 0);
  return (
    <div className="space-y-3">
      <div role="img" aria-label={`${label}: ${shown.map((s) => `${s.label} ${s.value}`).join(", ")}`} className="flex h-3 gap-0.5">
        {total === 0 && <div className="h-full flex-1 rounded-full bg-muted" />}
        {shown.map((s, i) => (
          <Tooltip key={s.key}>
            <TooltipTrigger
              render={
                <div
                  className={cn("h-full min-w-1.5 transition-opacity hover:opacity-80", i === 0 && "rounded-l-full", i === shown.length - 1 && "rounded-r-full")}
                  style={{ width: `${(s.value / total) * 100}%`, background: s.color }}
                />
              }
            />
            <TooltipContent>
              {s.label}: {s.value} {unit} ({Math.round((s.value / total) * 100)}%)
            </TooltipContent>
          </Tooltip>
        ))}
      </div>
      <Legend items={segments} />
    </div>
  );
}

/** Horizontal bars from one baseline, largest value full width, the value at the bar's tip. */
export function BarList({
  rows,
  unit,
}: {
  rows: { key: string; code?: string; label: string; value: number; color: string }[];
  unit: string;
}) {
  const max = Math.max(1, ...rows.map((r) => r.value));
  return (
    <ul className="space-y-2.5">
      {rows.map((r) => (
        <li key={r.key} className="grid grid-cols-[minmax(0,16rem)_1fr] items-center gap-4 text-[13px] max-sm:grid-cols-1 max-sm:gap-1">
          <span className="flex min-w-0 items-center gap-2">
            {r.code && <span className="grid size-5 shrink-0 place-items-center rounded bg-muted font-mono text-[11px] font-semibold">{r.code}</span>}
            <span className="truncate text-muted-foreground">{r.label}</span>
          </span>
          <Tooltip>
            <TooltipTrigger
              render={
                <div className="flex items-center gap-2">
                  <div className="h-3.5 min-w-1 rounded-r-[4px]" style={{ width: `calc(${(r.value / max) * 100}% - 2.5rem)`, background: r.color }} />
                  <span className="font-medium tabular-nums">{r.value}</span>
                </div>
              }
            />
            <TooltipContent>
              {r.label}: {r.value} {unit}
            </TooltipContent>
          </Tooltip>
        </li>
      ))}
    </ul>
  );
}

/** A ring for one share of a whole, with the figure in the middle. */
export function Gauge({ value, label, caption, size = 132 }: { value: number; label: string; caption?: string; size?: number }) {
  const stroke = 10;
  const r = (size - stroke) / 2;
  const c = 2 * Math.PI * r;
  const share = Math.max(0, Math.min(1, value));
  return (
    <div className="relative shrink-0" style={{ width: size, height: size }} role="img" aria-label={`${label}: ${Math.round(share * 100)}%`}>
      <svg width={size} height={size} className="-rotate-90">
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" strokeWidth={stroke} className="stroke-brand/15" />
        <circle
          cx={size / 2}
          cy={size / 2}
          r={r}
          fill="none"
          strokeWidth={stroke}
          strokeLinecap="round"
          strokeDasharray={`${c * share} ${c}`}
          className="stroke-brand transition-[stroke-dasharray] duration-700"
        />
      </svg>
      <div className="absolute inset-0 grid place-content-center text-center">
        <div className="text-[28px] leading-8 font-semibold tracking-tight">{Math.round(share * 100)}%</div>
        {caption && <div className="text-[11px] text-muted-foreground">{caption}</div>}
      </div>
    </div>
  );
}

export interface ColumnDay {
  /** ISO date, yyyy-mm-dd. */
  date: string;
  parts: Segment[];
}

const dayLabel = (iso: string) => new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", timeZone: "UTC" }).format(new Date(`${iso}T00:00:00Z`));

/** Counts per day as stacked columns on one axis. `series` fixes the stacking order and the legend. */
export function ColumnChart({ days, series, unit, height = 150 }: { days: ColumnDay[]; series: Omit<Segment, "value">[]; unit: string; height?: number }) {
  const totals = days.map((d) => d.parts.reduce((n, p) => n + p.value, 0));
  const max = Math.max(1, ...totals);
  // A clean top tick at or above the largest day.
  const top = max <= 4 ? max : Math.ceil(max / 2) * 2;
  const ticks = top <= 4 ? Array.from({ length: top + 1 }, (_, i) => i) : [0, top / 2, top];
  const legend = series.map((s) => ({ ...s, value: days.reduce((n, d) => n + (d.parts.find((p) => p.key === s.key)?.value ?? 0), 0) }));
  const labelEvery = Math.ceil(days.length / 7);
  return (
    <div className="space-y-3">
      <div className="flex gap-2">
        <div className="relative w-5 shrink-0 text-right text-[11px] text-muted-foreground tabular-nums" style={{ height }}>
          {ticks.map((t) => (
            <span key={t} className="absolute right-0 translate-y-1/2" style={{ bottom: `${(t / top) * 100}%` }}>
              {t}
            </span>
          ))}
        </div>
        <div className="min-w-0 flex-1">
          <div className="relative" style={{ height }}>
            {ticks.map((t) => (
              <div key={t} className={cn("absolute inset-x-0 border-t", t === 0 ? "border-border" : "border-grid")} style={{ bottom: `${(t / top) * 100}%` }} />
            ))}
            <div className="absolute inset-0 flex items-end">
              {days.map((d, i) => (
                <Tooltip key={d.date}>
                  <TooltipTrigger
                    render={
                      <div className="group flex h-full min-w-0 flex-1 flex-col-reverse items-center gap-0.5 hover:bg-foreground/[0.04]" aria-label={`${dayLabel(d.date)}: ${totals[i]} ${unit}`} />
                    }
                  >
                    {d.parts
                      .filter((p) => p.value > 0)
                      .map((p, j, shown) => (
                        <div
                          key={p.key}
                          className={cn("w-full max-w-5 shrink-0", j === shown.length - 1 && "rounded-t-[4px]")}
                          style={{ height: `calc(${(p.value / top) * 100}% - ${j > 0 ? 2 : 0}px)`, background: p.color }}
                        />
                      ))}
                  </TooltipTrigger>
                  <TooltipContent className="block">
                    <div className="font-medium">{dayLabel(d.date)}</div>
                    {totals[i] === 0 ? (
                      <div className="opacity-80">No {unit}</div>
                    ) : (
                      d.parts
                        .filter((p) => p.value > 0)
                        .map((p) => (
                          <div key={p.key} className="flex items-center gap-1.5">
                            <span className="size-2 rounded-[2px]" style={{ background: p.color }} />
                            {p.label}: {p.value}
                          </div>
                        ))
                    )}
                  </TooltipContent>
                </Tooltip>
              ))}
            </div>
          </div>
          <div className="mt-1.5 flex text-[11px] text-muted-foreground">
            {days.map((d, i) => (
              <span key={d.date} className="min-w-0 flex-1 text-center whitespace-nowrap">
                {(days.length - 1 - i) % labelEvery === 0 ? dayLabel(d.date) : ""}
              </span>
            ))}
          </div>
        </div>
      </div>
      <Legend items={legend} className="pl-7" />
    </div>
  );
}
