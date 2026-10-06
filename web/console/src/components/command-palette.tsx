"use client";

import { useQuery } from "@tanstack/react-query";
import { CornerDownLeft, FolderGit2, Plus, Search, Workflow, type LucideIcon } from "lucide-react";
import { useRouter } from "next/navigation";
import { useMemo, useState } from "react";

import { NAV } from "@/components/app-shell";
import { STATUS_LABEL } from "@/components/status";
import { Dialog, DialogContent, DialogTitle } from "@/components/ui/dialog";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { ago } from "@/lib/format";
import { cn } from "@/lib/utils";

interface Entry {
  group: string;
  label: string;
  hint?: string;
  icon: LucideIcon;
  href: string;
  /** Extra words the entry answers to. */
  keywords?: string;
}

/** Jump to a page, project or migration from the keyboard (Ctrl K or ⌘K). */
export function CommandPalette({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent showCloseButton={false} className="top-[18%] translate-y-0 gap-0 overflow-hidden p-0 sm:max-w-xl">
        <DialogTitle className="sr-only">Search</DialogTitle>
        {/* Mounted only while open, so the query and the selection start fresh each time. */}
        {open && <Palette close={() => onOpenChange(false)} />}
      </DialogContent>
    </Dialog>
  );
}

function Palette({ close }: { close: () => void }) {
  const [query, setQuery] = useState("");
  const [selected, setSelected] = useState(0);
  const router = useRouter();
  const auth = useAuth();
  const projects = useQuery({ queryKey: ["projects"], queryFn: api.projects });
  const migrations = useQuery({ queryKey: ["migrations"], queryFn: api.migrations });

  const entries = useMemo(() => {
    const all: Entry[] = [];
    for (const group of NAV) {
      for (const item of group.items) {
        if (!item.role || auth.can(item.role)) all.push({ group: "Go to", label: item.label, icon: item.icon, href: item.href });
      }
    }
    if (auth.can("ADMIN")) {
      all.push({ group: "Actions", label: "Add a project", icon: Plus, href: "/projects?add=1", keywords: "new register" });
    }
    for (const p of projects.data ?? []) {
      all.push({ group: "Projects", label: p.name, hint: p.path, icon: FolderGit2, href: `/projects/${p.id}`, keywords: p.ecosystem });
    }
    for (const m of (migrations.data ?? []).slice(0, 30)) {
      all.push({
        group: "Migrations",
        label: `${m.projectName} · ${STATUS_LABEL[m.status]}`,
        hint: `${m.id} · ${ago(m.createdAt)}`,
        icon: Workflow,
        href: `/migrations/${m.id}`,
      });
    }
    const words = query.toLowerCase().split(/\s+/).filter(Boolean);
    const matching = all.filter((e) => {
      const text = `${e.label} ${e.hint ?? ""} ${e.keywords ?? ""} ${e.group}`.toLowerCase();
      return words.every((w) => text.includes(w));
    });
    // Without a query, the long lists would bury the navigation.
    return words.length ? matching.slice(0, 40) : matching.filter((e) => e.group !== "Migrations").slice(0, 14);
  }, [query, projects.data, migrations.data, auth]);

  const index = Math.min(selected, Math.max(0, entries.length - 1));
  const go = (entry: Entry | undefined) => {
    if (!entry) return;
    close();
    router.push(entry.href);
  };

  return (
    <div
      onKeyDown={(e) => {
        if (e.key === "ArrowDown") {
          e.preventDefault();
          setSelected((index + 1) % Math.max(1, entries.length));
        } else if (e.key === "ArrowUp") {
          e.preventDefault();
          setSelected((index - 1 + entries.length) % Math.max(1, entries.length));
        } else if (e.key === "Enter") {
          e.preventDefault();
          go(entries[index]);
        }
      }}
    >
      <div className="flex items-center gap-2.5 border-b px-4">
        <Search className="size-4 shrink-0 text-muted-foreground" />
        <input
          autoFocus
          value={query}
          onChange={(e) => {
            setQuery(e.target.value);
            setSelected(0);
          }}
          placeholder="Search projects, migrations and pages"
          aria-label="Search"
          className="h-12 w-full bg-transparent text-sm outline-none placeholder:text-muted-foreground"
        />
      </div>
      <div className="scroll-thin max-h-[22rem] overflow-y-auto p-2" role="listbox" aria-label="Results">
        {entries.length === 0 && <div className="px-3 py-8 text-center text-sm text-muted-foreground">Nothing matches “{query}”.</div>}
        {entries.map((entry, i) => (
          <div key={`${entry.group}:${entry.href}`}>
            {(i === 0 || entries[i - 1].group !== entry.group) && (
              <div className="px-2.5 pt-2 pb-1 text-[11px] font-medium tracking-wider text-muted-foreground uppercase">{entry.group}</div>
            )}
            <button
              type="button"
              role="option"
              aria-selected={i === index}
              onMouseMove={() => setSelected(i)}
              onClick={() => go(entry)}
              ref={(el) => {
                if (i === index) el?.scrollIntoView({ block: "nearest" });
              }}
              className={cn("flex w-full items-center gap-3 rounded-md px-2.5 py-2 text-left text-sm outline-none", i === index && "bg-accent")}
            >
              <entry.icon className="size-4 shrink-0 text-muted-foreground" />
              <span className="min-w-0 flex-1">
                <span className="block truncate">{entry.label}</span>
                {entry.hint && <span className="block truncate font-mono text-[11px] text-muted-foreground">{entry.hint}</span>}
              </span>
              {i === index && <CornerDownLeft className="size-3.5 shrink-0 text-muted-foreground" />}
            </button>
          </div>
        ))}
      </div>
      <div className="flex items-center gap-4 border-t bg-muted/40 px-4 py-2 text-[11px] text-muted-foreground">
        <span><kbd className="font-sans">↑↓</kbd> to move</span>
        <span><kbd className="font-sans">Enter</kbd> to open</span>
        <span><kbd className="font-sans">Esc</kbd> to close</span>
      </div>
    </div>
  );
}
