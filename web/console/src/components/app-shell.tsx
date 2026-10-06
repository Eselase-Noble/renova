"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Activity,
  BookOpenCheck,
  Building2,
  Check,
  ChevronRight,
  ChevronsUpDown,
  FolderGit2,
  KeyRound,
  Laptop,
  LayoutDashboard,
  LogOut,
  Menu,
  Monitor,
  Moon,
  PanelLeftClose,
  PanelLeftOpen,
  Plus,
  ScrollText,
  Search,
  Settings,
  Sun,
  Users,
  Workflow,
  type LucideIcon,
} from "lucide-react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useTheme } from "next-themes";
import { useEffect, useState } from "react";
import { toast } from "sonner";

import { ChangePasswordDialog, NewOrganisationDialog } from "@/components/account-dialogs";
import { LogoMark, Wordmark } from "@/components/brand";
import { CommandPalette } from "@/components/command-palette";
import { Avatar } from "@/components/page";
import { StatusDot } from "@/components/status";
import { Dialog, DialogContent, DialogTitle } from "@/components/ui/dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Skeleton } from "@/components/ui/skeleton";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { useStored } from "@/hooks/use-stored";
import { active, api, type Role } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { cn } from "@/lib/utils";

interface NavItem {
  href: string;
  label: string;
  icon: LucideIcon;
  /** The least role that sees the item. */
  role?: Role;
  /** Only where there are other people: hidden in local mode. */
  team?: boolean;
}

export const NAV: { title: string; items: NavItem[] }[] = [
  {
    title: "Workspace",
    items: [
      { href: "/", label: "Overview", icon: LayoutDashboard },
      { href: "/projects", label: "Projects", icon: FolderGit2 },
      { href: "/migrations", label: "Migrations", icon: Workflow },
      { href: "/playbooks", label: "Playbooks", icon: BookOpenCheck },
    ],
  },
  {
    title: "Organisation",
    items: [
      { href: "/organisation", label: "Members", icon: Users, team: true },
      { href: "/audit", label: "Audit log", icon: ScrollText, role: "ADMIN", team: true },
      { href: "/settings", label: "Settings", icon: Settings },
    ],
  },
];

/** Pages that work without signing in; they are shown without the navigation. */
const PUBLIC = ["/login", "/setup", "/invite"];

const isActive = (pathname: string, href: string) => (href === "/" ? pathname === "/" : pathname === href || pathname.startsWith(`${href}/`));

export function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const isPublic = PUBLIC.some((p) => pathname === p || pathname.startsWith(`${p}/`));
  const auth = useAuth();
  const signedOut = auth.data && !auth.data.user;
  const [collapsed, setCollapsed] = useStored("renova.sidebar", "open");
  const [mobileOpen, setMobileOpen] = useState(false);
  const [paletteOpen, setPaletteOpen] = useState(false);

  useEffect(() => {
    if (!isPublic && signedOut) {
      router.replace(auth.data?.setupRequired ? "/setup" : `/login?next=${encodeURIComponent(pathname)}`);
    }
  }, [isPublic, signedOut, auth.data?.setupRequired, pathname, router]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setPaletteOpen((open) => !open);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  if (isPublic) return <>{children}</>;
  if (!auth.data?.user) {
    return (
      <div className="flex min-h-svh">
        <div className="hidden w-60 shrink-0 border-r border-sidebar-border bg-sidebar lg:block" />
        <div className="flex-1 space-y-4 p-8">
          <Skeleton className="h-8 w-56" />
          <Skeleton className="h-28 w-full max-w-5xl" />
          <Skeleton className="h-64 w-full max-w-5xl" />
        </div>
      </div>
    );
  }

  const narrow = collapsed === "closed";
  return (
    <div className="flex min-h-svh">
      <aside
        className={cn(
          "sticky top-0 hidden h-svh shrink-0 flex-col border-r border-sidebar-border bg-sidebar text-sidebar-foreground transition-[width] duration-200 lg:flex",
          narrow ? "w-[60px]" : "w-60",
        )}
      >
        <SidebarContent narrow={narrow} />
        <div className={cn("border-t border-sidebar-border p-2", narrow && "flex justify-center")}>
          <button
            type="button"
            onClick={() => setCollapsed(narrow ? "open" : "closed")}
            aria-label={narrow ? "Expand the sidebar" : "Collapse the sidebar"}
            className="flex h-8 w-full items-center gap-2.5 rounded-md px-2.5 text-[13px] text-sidebar-foreground/70 outline-none hover:bg-sidebar-accent hover:text-sidebar-accent-foreground focus-visible:ring-2 focus-visible:ring-sidebar-ring"
          >
            {narrow ? <PanelLeftOpen className="size-4 shrink-0" /> : <PanelLeftClose className="size-4 shrink-0" />}
            {!narrow && "Collapse"}
          </button>
        </div>
      </aside>

      <Dialog open={mobileOpen} onOpenChange={setMobileOpen}>
        <DialogContent
          showCloseButton={false}
          className="top-0 left-0 flex h-svh w-64 max-w-[80vw] translate-x-0 translate-y-0 flex-col gap-0 rounded-none bg-sidebar p-0 text-sidebar-foreground ring-0 sm:max-w-64 data-open:slide-in-from-left data-open:zoom-in-100 data-closed:slide-out-to-left data-closed:zoom-out-100"
        >
          <DialogTitle className="sr-only">Navigation</DialogTitle>
          <SidebarContent narrow={false} onNavigate={() => setMobileOpen(false)} />
        </DialogContent>
      </Dialog>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="sticky top-0 z-30 flex h-14 shrink-0 items-center gap-3 border-b bg-background/85 px-4 backdrop-blur-md md:px-6">
          <button
            type="button"
            onClick={() => setMobileOpen(true)}
            aria-label="Open navigation"
            className="-ml-1 grid size-8 place-items-center rounded-md text-muted-foreground outline-none hover:bg-muted hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring lg:hidden"
          >
            <Menu className="size-5" />
          </button>
          <Breadcrumbs />
          <div className="ml-auto flex items-center gap-1.5">
            <button
              type="button"
              onClick={() => setPaletteOpen(true)}
              className="flex h-8 items-center gap-2 rounded-lg border bg-card px-2.5 text-[13px] text-muted-foreground shadow-(--shadow-card) outline-none hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring md:w-64"
            >
              <Search className="size-4" />
              <span className="hidden md:inline">Search or jump to…</span>
              <kbd className="ml-auto hidden rounded border bg-muted px-1.5 font-sans text-[11px] leading-5 md:inline">Ctrl K</kbd>
            </button>
            <ActiveMigrations />
            <ThemeMenu />
            <UserMenu />
          </div>
        </header>
        <main className="flex-1">
          <div className="mx-auto w-full max-w-[1320px] px-4 py-6 md:px-8 md:py-8">{children}</div>
        </main>
      </div>
      <CommandPalette open={paletteOpen} onOpenChange={setPaletteOpen} />
    </div>
  );
}

function SidebarContent({ narrow, onNavigate }: { narrow: boolean; onNavigate?: () => void }) {
  const pathname = usePathname();
  const auth = useAuth();
  const system = useQuery({ queryKey: ["system"], queryFn: api.system, staleTime: Infinity });
  return (
    <>
      <div className={cn("flex h-14 shrink-0 items-center gap-2.5 px-4", narrow && "justify-center px-0")}>
        <Link href="/" onClick={onNavigate} className="flex items-center gap-2.5 rounded-md outline-none focus-visible:ring-2 focus-visible:ring-sidebar-ring">
          <LogoMark className="size-7" />
          {!narrow && <Wordmark className="text-sidebar-accent-foreground" />}
        </Link>
      </div>
      {auth.local ? (
        !narrow && (
          <div className="mx-2 mb-2 flex items-center gap-2.5 rounded-lg border border-sidebar-border bg-sidebar-accent/40 px-2.5 py-2">
            <Laptop className="size-4 shrink-0 text-sidebar-accent-foreground" />
            <span className="min-w-0">
              <span className="block text-[13px] leading-4 font-medium text-sidebar-accent-foreground">Local mode</span>
              <span className="block text-[11px] leading-4 text-sidebar-foreground/60">Your code stays on this machine</span>
            </span>
          </div>
        )
      ) : (
        <div className={cn("px-2 pb-2", narrow && "flex justify-center")}>
          <OrganisationSwitcher narrow={narrow} />
        </div>
      )}
      <nav className="scroll-thin flex-1 space-y-5 overflow-y-auto px-2 py-2" aria-label="Main">
        {NAV.map((group) => {
          const items = group.items.filter((i) => (!i.role || auth.can(i.role)) && !(i.team && auth.local));
          return (
            <div key={group.title} className="space-y-0.5">
              {narrow ? (
                <div className="mx-2 mb-2 border-t border-sidebar-border first:hidden" />
              ) : (
                <div className="px-2.5 pb-1 text-[11px] font-medium tracking-wider text-sidebar-foreground/60 uppercase">
                  {auth.local && group.title === "Organisation" ? "This computer" : group.title}
                </div>
              )}
              {items.map(({ href, label, icon: Icon }) => {
                const current = isActive(pathname, href);
                const link = (
                  <Link
                    key={href}
                    href={href}
                    onClick={onNavigate}
                    aria-current={current ? "page" : undefined}
                    className={cn(
                      "relative flex h-8 items-center gap-2.5 rounded-md px-2.5 text-[13px] font-medium text-sidebar-foreground outline-none transition-colors hover:bg-sidebar-accent hover:text-sidebar-accent-foreground focus-visible:ring-2 focus-visible:ring-sidebar-ring",
                      current && "bg-sidebar-accent text-sidebar-accent-foreground",
                      narrow && "justify-center px-0",
                    )}
                  >
                    {current && <span className="absolute top-1.5 bottom-1.5 -left-2 w-[3px] rounded-r-full bg-sidebar-primary" />}
                    <Icon className={cn("size-4 shrink-0", current ? "text-sidebar-accent-foreground" : "opacity-80")} />
                    {!narrow && label}
                  </Link>
                );
                return narrow ? (
                  <Tooltip key={href}>
                    <TooltipTrigger render={link} />
                    <TooltipContent side="right">{label}</TooltipContent>
                  </Tooltip>
                ) : (
                  link
                );
              })}
            </div>
          );
        })}
      </nav>
      {!narrow && system.data && (
        <div className="px-4 pb-3 text-[11px] text-sidebar-foreground/60">
          Renova {system.data.version.replace("-SNAPSHOT", "")} · {auth.local ? "this machine only" : "on-premises"}
        </div>
      )}
    </>
  );
}

function OrganisationSwitcher({ narrow }: { narrow: boolean }) {
  const auth = useAuth();
  const queryClient = useQueryClient();
  const router = useRouter();
  const [creating, setCreating] = useState(false);
  const current = auth.data?.organisation;
  const switchTo = useMutation({
    mutationFn: api.switchOrganisation,
    onSuccess: (state) => {
      queryClient.clear();
      queryClient.setQueryData(["auth"], state);
      router.push("/");
      toast.success(`Switched to ${state.organisation?.name}`);
    },
    onError: (e) => toast.error(e.message),
  });
  if (!current) return null;
  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger
          render={
            <button
              type="button"
              aria-label={`Organisation: ${current.name}`}
              className={cn(
                "flex items-center gap-2.5 rounded-lg border border-sidebar-border bg-sidebar-accent/40 text-left outline-none transition-colors hover:bg-sidebar-accent focus-visible:ring-2 focus-visible:ring-sidebar-ring",
                narrow ? "size-9 justify-center" : "h-11 w-full px-2",
              )}
            />
          }
        >
          <span className="grid size-6 shrink-0 place-items-center rounded-md bg-sidebar-primary/20 text-[11px] font-semibold text-sidebar-accent-foreground">
            {current.name.slice(0, 1).toUpperCase()}
          </span>
          {!narrow && (
            <>
              <span className="min-w-0 flex-1">
                <span className="block truncate text-[13px] leading-4 font-medium text-sidebar-accent-foreground">{current.name}</span>
                <span className="block text-[11px] leading-4 text-sidebar-foreground/60 capitalize">{current.role.toLowerCase()}</span>
              </span>
              <ChevronsUpDown className="size-3.5 shrink-0 text-sidebar-foreground/50" />
            </>
          )}
        </DropdownMenuTrigger>
        <DropdownMenuContent className="w-60" align="start">
          <DropdownMenuGroup>
            <DropdownMenuLabel>Organisations</DropdownMenuLabel>
            {auth.data?.organisations.map((o) => (
              <DropdownMenuItem key={o.id} onClick={() => o.id !== current.id && switchTo.mutate(o.id)}>
                <Building2 />
                <span className={cn("truncate", o.id === current.id && "font-medium")}>{o.name}</span>
                {o.id === current.id ? (
                  <Check className="ml-auto text-brand" />
                ) : (
                  <span className="ml-auto text-xs text-muted-foreground capitalize">{o.role.toLowerCase()}</span>
                )}
              </DropdownMenuItem>
            ))}
          </DropdownMenuGroup>
          <DropdownMenuSeparator />
          <DropdownMenuItem onClick={() => setCreating(true)}>
            <Plus /> New organisation
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
      <NewOrganisationDialog open={creating} onOpenChange={setCreating} />
    </>
  );
}

const SECTION_TITLES: Record<string, string> = {
  projects: "Projects",
  migrations: "Migrations",
  playbooks: "Playbooks",
  organisation: "Members",
  audit: "Audit log",
  settings: "Settings",
};

/** Where the page sits: the section, then the project or migration being looked at. */
function Breadcrumbs() {
  const pathname = usePathname();
  const [section, id] = pathname.split("/").filter(Boolean);
  const project = useQuery({ queryKey: ["project", id], queryFn: () => api.project(id), enabled: section === "projects" && !!id });
  const migration = useQuery({ queryKey: ["migration", id], queryFn: () => api.migration(id), enabled: section === "migrations" && !!id });
  const crumbs: { label: string; href?: string }[] = [];
  if (!section) {
    crumbs.push({ label: "Overview" });
  } else {
    crumbs.push({ label: SECTION_TITLES[section] ?? section, href: id ? `/${section}` : undefined });
    if (section === "projects" && id) crumbs.push({ label: project.data?.name ?? "…" });
    if (section === "migrations" && id) {
      const m = migration.data?.migration;
      if (m) crumbs.push({ label: m.projectName, href: `/projects/${m.projectId}` });
      crumbs.push({ label: id });
    }
  }
  return (
    <nav aria-label="Breadcrumb" className="flex min-w-0 items-center gap-1.5 text-sm">
      {crumbs.map((c, i) => (
        <span key={i} className="flex min-w-0 items-center gap-1.5">
          {i > 0 && <ChevronRight className="size-3.5 shrink-0 text-muted-foreground/60" />}
          {c.href ? (
            <Link href={c.href} className="truncate text-muted-foreground hover:text-foreground">
              {c.label}
            </Link>
          ) : (
            <span className={cn("truncate font-medium", i > 0 && i === crumbs.length - 1 && crumbs.length > 2 && "font-mono text-[13px] font-normal")}>
              {c.label}
            </span>
          )}
        </span>
      ))}
    </nav>
  );
}

/** Migrations that are queued or running, one click away from every page. */
function ActiveMigrations() {
  const migrations = useQuery({
    queryKey: ["migrations"],
    queryFn: api.migrations,
    refetchInterval: (q) => (q.state.data?.some((m) => active(m.status)) ? 3000 : 30_000),
  });
  const running = (migrations.data ?? []).filter((m) => active(m.status));
  const router = useRouter();
  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={
          <button
            type="button"
            aria-label={running.length ? `${running.length} migration(s) in progress` : "No migration in progress"}
            className="relative grid size-8 place-items-center rounded-lg text-muted-foreground outline-none hover:bg-muted hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring"
          />
        }
      >
        <Activity className="size-4" />
        {running.length > 0 && (
          <span className="absolute -top-0.5 -right-0.5 grid h-4 min-w-4 place-items-center rounded-full bg-info px-1 text-[10px] font-semibold text-background">
            {running.length}
          </span>
        )}
      </DropdownMenuTrigger>
      <DropdownMenuContent className="w-72" align="end">
        <DropdownMenuGroup>
          <DropdownMenuLabel>In progress</DropdownMenuLabel>
          {running.length === 0 && <div className="px-2 py-3 text-[13px] text-muted-foreground">No migration is queued or running.</div>}
          {running.map((m) => (
            <DropdownMenuItem key={m.id} onClick={() => router.push(`/migrations/${m.id}`)}>
              <StatusDot status={m.status} />
              <span className="min-w-0 flex-1">
                <span className="block truncate font-medium">{m.projectName}</span>
                <span className="block truncate font-mono text-[11px] text-muted-foreground">{m.id}</span>
              </span>
              <span className="text-xs text-muted-foreground capitalize">{m.status.toLowerCase()}</span>
            </DropdownMenuItem>
          ))}
        </DropdownMenuGroup>
        <DropdownMenuSeparator />
        <DropdownMenuItem onClick={() => router.push("/migrations")}>
          <Workflow /> All migrations
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

function UserMenu() {
  const auth = useAuth();
  const router = useRouter();
  const queryClient = useQueryClient();
  const [changing, setChanging] = useState(false);
  const signOut = useMutation({
    mutationFn: api.logout,
    onSettled: () => {
      queryClient.clear();
      router.replace("/login");
    },
  });
  const user = auth.data?.user;
  if (!user) return null;
  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger
          render={
            <button
              type="button"
              aria-label={`Account: ${user.name}`}
              className="ml-1 rounded-full outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background"
            />
          }
        >
          <Avatar name={user.name} className="size-8 text-xs" />
        </DropdownMenuTrigger>
        <DropdownMenuContent className="w-60" align="end">
          <div className="flex items-center gap-2.5 px-2 py-2">
            <Avatar name={user.name} className="size-8 text-xs" />
            <div className="min-w-0">
              <div className="truncate text-sm font-medium">{user.name}</div>
              <div className="truncate text-xs text-muted-foreground">{auth.local ? "This computer" : user.email}</div>
            </div>
          </div>
          <DropdownMenuSeparator />
          {auth.local ? (
            <div className="px-2 py-2 text-xs text-muted-foreground">
              Local mode: no sign-in. Renova answers only this machine, and projects and migrated copies stay on it.
            </div>
          ) : (
            <>
              <DropdownMenuItem onClick={() => setChanging(true)}>
                <KeyRound /> Change password
              </DropdownMenuItem>
              <DropdownMenuItem onClick={() => signOut.mutate()}>
                <LogOut /> Sign out
              </DropdownMenuItem>
            </>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
      <ChangePasswordDialog open={changing} onOpenChange={setChanging} />
    </>
  );
}

function ThemeMenu() {
  const { theme, setTheme } = useTheme();
  const options = [
    { value: "light", label: "Light", icon: Sun },
    { value: "dark", label: "Dark", icon: Moon },
    { value: "system", label: "System", icon: Monitor },
  ];
  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={
          <button
            type="button"
            aria-label="Theme"
            className="grid size-8 place-items-center rounded-lg text-muted-foreground outline-none hover:bg-muted hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring"
          />
        }
      >
        <Sun className="size-4 dark:hidden" />
        <Moon className="hidden size-4 dark:block" />
      </DropdownMenuTrigger>
      <DropdownMenuContent className="w-40" align="end">
        {options.map(({ value, label, icon: Icon }) => (
          <DropdownMenuItem key={value} onClick={() => setTheme(value)}>
            <Icon /> {label}
            {theme === value && <Check className="ml-auto text-brand" />}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
