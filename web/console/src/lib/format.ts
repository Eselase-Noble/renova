const relative = new Intl.RelativeTimeFormat("en", { numeric: "auto" });

/** "3 minutes ago", "yesterday". */
export function ago(iso: string | null | undefined): string {
  if (!iso) return "—";
  const seconds = (new Date(iso).getTime() - Date.now()) / 1000;
  const units: [Intl.RelativeTimeFormatUnit, number][] = [
    ["year", 31536000],
    ["month", 2592000],
    ["day", 86400],
    ["hour", 3600],
    ["minute", 60],
  ];
  for (const [unit, size] of units) {
    if (Math.abs(seconds) >= size) return relative.format(Math.round(seconds / size), unit);
  }
  return "just now";
}

export function duration(from: string | null, to: string | null): string {
  if (!from) return "—";
  const ms = (to ? new Date(to).getTime() : Date.now()) - new Date(from).getTime();
  const s = Math.max(0, Math.round(ms / 1000));
  return s < 60 ? `${s}s` : `${Math.floor(s / 60)}m ${s % 60}s`;
}

export const percent = (rate: number) => `${Math.round(rate * 100)}%`;

export const tokens = (n: number) => (n >= 1000 ? `${(n / 1000).toFixed(1)}K` : String(n));

export const CATEGORY_NAMES: Record<string, string> = {
  A: "Build, platform and descriptors",
  B: "Namespace renames",
  C: "Removed or changed APIs",
  D: "Runtime and container behaviour",
  E: "Dependency declarations",
};

export const STRATEGY_NAMES: Record<string, string> = {
  recipe: "Recipe",
  replace: "Text rule",
  maven: "Build file edit",
  ai: "AI",
  manual: "Manual",
};

/** "6 Oct 2026, 12:21", for tooltips beside a relative time. */
export function dateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  return new Intl.DateTimeFormat("en-GB", { dateStyle: "medium", timeStyle: "short" }).format(new Date(iso));
}

export const initials = (name: string) =>
  name
    .split(/\s+/)
    .filter(Boolean)
    .map((p) => p[0])
    .slice(0, 2)
    .join("")
    .toUpperCase();

/** Deterministic and judged work, as the three groups the console reports on. */
export type Resolver = "automatic" | "ai" | "person";
export const RESOLVER_OF: Record<string, Resolver> = { recipe: "automatic", replace: "automatic", maven: "automatic", ai: "ai", manual: "person" };
export const RESOLVER_NAMES: Record<Resolver, string> = { automatic: "Automatic", ai: "AI, checked by the build", person: "A person" };
export const resolverOf = (strategy: string): Resolver => RESOLVER_OF[strategy] ?? "person";

export const plural = (n: number, one: string, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;
