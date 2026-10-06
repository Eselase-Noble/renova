"use client";

import { ChevronRight, FileCode2 } from "lucide-react";
import { useMemo, useState } from "react";

import { cn } from "@/lib/utils";

interface DiffLine {
  kind: "add" | "del" | "ctx" | "hunk" | "meta";
  text: string;
  /** Line numbers in the file before and after; absent where the line does not exist. */
  before?: number;
  after?: number;
}

interface FileDiff {
  path: string;
  lines: DiffLine[];
  added: number;
  removed: number;
}

const MAX_LINES = 1500;

/** Splits a unified diff (git show --patch) into files, numbering each line from its hunk header. */
export function parseDiff(diff: string): FileDiff[] {
  const files: FileDiff[] = [];
  let current: FileDiff | null = null;
  let before = 0;
  let after = 0;
  for (const line of diff.split("\n")) {
    if (line.startsWith("diff --git ")) {
      const match = line.match(/ b\/(.*)$/);
      current = { path: match ? match[1] : line, lines: [], added: 0, removed: 0 };
      files.push(current);
    } else if (current) {
      if (line.startsWith("index ") || line.startsWith("--- ") || line.startsWith("+++ ")) continue;
      const hunk = line.match(/^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/);
      if (hunk) {
        before = Number(hunk[1]);
        after = Number(hunk[2]);
        current.lines.push({ kind: "hunk", text: line });
      } else if (line.startsWith("+")) {
        current.added++;
        current.lines.push({ kind: "add", text: line.slice(1), after: after++ });
      } else if (line.startsWith("-")) {
        current.removed++;
        current.lines.push({ kind: "del", text: line.slice(1), before: before++ });
      } else if (line.startsWith(" ")) {
        current.lines.push({ kind: "ctx", text: line.slice(1), before: before++, after: after++ });
      } else if (line) {
        // "new file mode", "\ No newline at end of file", renames.
        current.lines.push({ kind: "meta", text: line });
      }
    }
  }
  return files;
}

export function DiffView({ diff }: { diff: string }) {
  const files = useMemo(() => parseDiff(diff), [diff]);
  if (files.length === 0) return <p className="rounded-xl border border-dashed p-8 text-center text-sm text-muted-foreground">No file changes in this stage.</p>;
  return (
    <div className="space-y-3">
      {files.map((f) => (
        <FileBlock key={f.path} file={f} initiallyOpen={files.length <= 8} />
      ))}
    </div>
  );
}

function FileBlock({ file, initiallyOpen }: { file: FileDiff; initiallyOpen: boolean }) {
  const [open, setOpen] = useState(initiallyOpen);
  const shown = file.lines.slice(0, MAX_LINES);
  const slash = file.path.lastIndexOf("/");
  return (
    <div className="overflow-hidden rounded-lg border bg-card">
      <button
        type="button"
        aria-expanded={open}
        className="flex w-full items-center gap-2 bg-muted/50 px-3 py-2 text-left font-mono text-xs outline-none hover:bg-muted focus-visible:bg-muted"
        onClick={() => setOpen(!open)}
      >
        <ChevronRight className={cn("size-3.5 shrink-0 text-muted-foreground transition-transform", open && "rotate-90")} />
        <FileCode2 className="size-3.5 shrink-0 text-muted-foreground" />
        <span className="min-w-0 flex-1 truncate">
          <span className="text-muted-foreground">{file.path.slice(0, slash + 1)}</span>
          <span className="font-medium">{file.path.slice(slash + 1)}</span>
        </span>
        <span className="text-success tabular-nums">+{file.added}</span>
        <span className="text-danger tabular-nums">−{file.removed}</span>
      </button>
      {open && (
        <div className="scroll-thin overflow-x-auto border-t font-mono text-xs leading-5">
          <table className="w-full border-collapse">
            <tbody>
              {shown.map((line, i) =>
                line.kind === "hunk" || line.kind === "meta" ? (
                  <tr key={i} className={line.kind === "hunk" ? "bg-info/8 text-info" : "text-muted-foreground"}>
                    <td colSpan={3} className="px-3 py-0.5 whitespace-pre">{line.text}</td>
                  </tr>
                ) : (
                  <tr key={i} className={cn(line.kind === "add" && "bg-success/10", line.kind === "del" && "bg-danger/10")}>
                    <td className="w-10 min-w-10 border-r px-2 text-right text-muted-foreground/70 select-none tabular-nums">{line.before ?? ""}</td>
                    <td className="w-10 min-w-10 border-r px-2 text-right text-muted-foreground/70 select-none tabular-nums">{line.after ?? ""}</td>
                    <td className="px-3 whitespace-pre">
                      <span aria-hidden className={cn("mr-2 inline-block w-2 select-none", line.kind === "add" && "text-success", line.kind === "del" && "text-danger")}>
                        {line.kind === "add" ? "+" : line.kind === "del" ? "−" : " "}
                      </span>
                      {line.text || " "}
                    </td>
                  </tr>
                ),
              )}
            </tbody>
          </table>
          {file.lines.length > MAX_LINES && <div className="border-t px-3 py-1.5 font-sans text-muted-foreground">{file.lines.length - MAX_LINES} more lines are not shown.</div>}
        </div>
      )}
    </div>
  );
}
