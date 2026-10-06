"use client";

import { ArrowDownToLine, Download, Loader2 } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";

import { CopyButton, SearchInput } from "@/components/page";
import { plural } from "@/lib/format";
import { cn } from "@/lib/utils";

/** How a progress line reads: the engine starts stages, reports trouble and finishes with fixed words. */
function tone(line: string): string {
  if (/^(Error|Cancelled)|\bfails?\b|\bfailed\b|differs/i.test(line)) return "text-red-300";
  if (/^Finished/.test(line)) return /FAILS|DIFFERENT|FAILED/.test(line) ? "text-red-300" : "text-emerald-300";
  if (/^(Stage |Verifying|Checking|Plan:)/.test(line)) return "text-white";
  return "text-slate-300";
}

/** A migration's progress as a console: follows the end while it runs, filters, copies and downloads. */
export function LogView({ lines, running, name }: { lines: string[]; running: boolean; name: string }) {
  const [query, setQuery] = useState("");
  const [follow, setFollow] = useState(true);
  const pane = useRef<HTMLDivElement>(null);
  const shown = useMemo(() => {
    const q = query.trim().toLowerCase();
    return lines.map((text, i) => ({ text, n: i + 1 })).filter((l) => !q || l.text.toLowerCase().includes(q));
  }, [lines, query]);

  useEffect(() => {
    if (running && follow && pane.current) pane.current.scrollTop = pane.current.scrollHeight;
  }, [shown.length, running, follow]);

  const download = () => {
    const url = URL.createObjectURL(new Blob([lines.join("\n") + "\n"], { type: "text/plain" }));
    const a = document.createElement("a");
    a.href = url;
    a.download = `${name}.log`;
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <section className="overflow-hidden rounded-xl border shadow-(--shadow-card)">
      <div className="flex flex-wrap items-center gap-2 border-b bg-card px-4 py-2.5">
        <SearchInput value={query} onChange={setQuery} placeholder="Filter the log" className="w-full sm:w-64" />
        <span className="text-xs text-muted-foreground">{query ? `${shown.length} of ${plural(lines.length, "line")}` : plural(lines.length, "line")}</span>
        <div className="ml-auto flex items-center gap-1">
          {running && (
            <button
              type="button"
              onClick={() => setFollow(!follow)}
              aria-pressed={follow}
              className={cn(
                "inline-flex h-7 items-center gap-1.5 rounded-md px-2 text-xs font-medium outline-none hover:bg-muted focus-visible:ring-2 focus-visible:ring-ring",
                follow ? "text-brand" : "text-muted-foreground",
              )}
            >
              <ArrowDownToLine className="size-3.5" /> Follow
            </button>
          )}
          <CopyButton value={lines.join("\n")} label="Copy the log" className="size-7" />
          <button
            type="button"
            onClick={download}
            aria-label="Download the log"
            title="Download the log"
            className="grid size-7 place-items-center rounded-md text-muted-foreground outline-none hover:bg-muted hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring"
          >
            <Download className="size-3.5" />
          </button>
        </div>
      </div>
      {/* Always dark, like the terminal the same lines appear in from the CLI. */}
      <div ref={pane} className="scroll-thin max-h-[34rem] min-h-40 overflow-auto bg-[oklch(0.17_0.02_268)] py-3 font-mono text-xs leading-6" role="log" aria-live="off">
        {shown.map((l) => (
          <div key={l.n} className="flex gap-4 px-4 hover:bg-white/5">
            <span className="w-8 shrink-0 text-right text-slate-500 select-none tabular-nums">{l.n}</span>
            <span className={cn("min-w-0 break-words whitespace-pre-wrap", tone(l.text))}>{l.text}</span>
          </div>
        ))}
        {shown.length === 0 && <div className="px-4 text-slate-400">{lines.length ? "No line matches the filter." : "Nothing logged yet."}</div>}
        {running && (
          <div className="flex items-center gap-2 px-4 pl-16 text-slate-400">
            <Loader2 className="size-3 animate-spin" /> working…
          </div>
        )}
      </div>
    </section>
  );
}
