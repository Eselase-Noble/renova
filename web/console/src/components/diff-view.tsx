"use client";

import { ChevronRight } from "lucide-react";
import { useMemo, useState } from "react";

import { cn } from "@/lib/utils";

interface FileDiff {
  path: string;
  lines: string[];
  added: number;
  removed: number;
}

const MAX_LINES = 1500;

/** Splits a unified diff (git show --patch) into files. */
export function parseDiff(diff: string): FileDiff[] {
  const files: FileDiff[] = [];
  let current: FileDiff | null = null;
  for (const line of diff.split("\n")) {
    if (line.startsWith("diff --git ")) {
      const match = line.match(/ b\/(.*)$/);
      current = { path: match ? match[1] : line, lines: [], added: 0, removed: 0 };
      files.push(current);
    } else if (current) {
      if (line.startsWith("index ") || line.startsWith("--- ") || line.startsWith("+++ ")) continue;
      if (line.startsWith("+")) current.added++;
      else if (line.startsWith("-")) current.removed++;
      current.lines.push(line);
    }
  }
  return files;
}

export function DiffView({ diff }: { diff: string }) {
  const files = useMemo(() => parseDiff(diff), [diff]);
  if (files.length === 0) return <p className="text-sm text-muted-foreground">No file changes in this stage.</p>;
  return (
    <div className="space-y-3">
      {files.map((f) => (
        <FileBlock key={f.path} file={f} initiallyOpen={files.length <= 6} />
      ))}
    </div>
  );
}

function FileBlock({ file, initiallyOpen }: { file: FileDiff; initiallyOpen: boolean }) {
  const [open, setOpen] = useState(initiallyOpen);
  const shown = file.lines.slice(0, MAX_LINES);
  return (
    <div className="overflow-hidden rounded-lg border">
      <button
        type="button"
        className="flex w-full items-center gap-2 bg-muted/50 px-3 py-2 text-left font-mono text-xs hover:bg-muted"
        onClick={() => setOpen(!open)}
      >
        <ChevronRight className={cn("size-3.5 shrink-0 transition-transform", open && "rotate-90")} />
        <span className="min-w-0 flex-1 truncate">{file.path}</span>
        <span className="text-emerald-600 dark:text-emerald-400">+{file.added}</span>
        <span className="text-red-600 dark:text-red-400">−{file.removed}</span>
      </button>
      {open && (
        <pre className="overflow-x-auto text-xs leading-5">
          {shown.map((line, i) => (
            <div
              key={i}
              className={cn(
                "px-3 whitespace-pre",
                line.startsWith("+") && "bg-emerald-500/10 text-emerald-800 dark:text-emerald-300",
                line.startsWith("-") && "bg-red-500/10 text-red-800 dark:text-red-300",
                line.startsWith("@@") && "bg-sky-500/10 text-sky-800 dark:text-sky-300",
              )}
            >
              {line || " "}
            </div>
          ))}
          {file.lines.length > MAX_LINES && (
            <div className="px-3 py-1 text-muted-foreground">… {file.lines.length - MAX_LINES} more lines</div>
          )}
        </pre>
      )}
    </div>
  );
}
