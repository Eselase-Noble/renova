"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Building2, ChevronsUpDown, FolderGit2, KeyRound, LayoutDashboard, LogOut, Moon, Play, Plus, Settings, Sun, Users } from "lucide-react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useTheme } from "next-themes";
import { useEffect, useState } from "react";
import { toast } from "sonner";

import { ChangePasswordDialog, NewOrganisationDialog } from "@/components/account-dialogs";
import { Button } from "@/components/ui/button";
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
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { cn } from "@/lib/utils";

const NAV = [
  { href: "/", label: "Dashboard", icon: LayoutDashboard },
  { href: "/projects", label: "Projects", icon: FolderGit2 },
  { href: "/migrations", label: "Migrations", icon: Play },
  { href: "/organisation", label: "Organisation", icon: Users },
  { href: "/settings", label: "Settings", icon: Settings },
];

/** Pages that work without signing in; they are shown without the navigation. */
const PUBLIC = ["/login", "/setup", "/invite"];

export function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const isPublic = PUBLIC.some((p) => pathname === p || pathname.startsWith(`${p}/`));
  const auth = useAuth();
  const signedOut = auth.data && !auth.data.user;

  useEffect(() => {
    if (!isPublic && signedOut) {
      router.replace(auth.data?.setupRequired ? "/setup" : `/login?next=${encodeURIComponent(pathname)}`);
    }
  }, [isPublic, signedOut, auth.data?.setupRequired, pathname, router]);

  if (isPublic) {
    return (
      <main className="grid min-h-svh place-items-center px-4 py-10">
        <div className="w-full max-w-sm">{children}</div>
      </main>
    );
  }
  if (!auth.data?.user) {
    return (
      <div className="mx-auto max-w-6xl space-y-3 p-8">
        <Skeleton className="h-8 w-48" />
        <Skeleton className="h-24 w-full" />
      </div>
    );
  }

  return (
    <div className="flex min-h-svh flex-col md:flex-row">
      <aside className="border-b bg-muted/30 md:w-60 md:shrink-0 md:border-r md:border-b-0">
        <div className="flex items-center justify-between gap-2 px-3 py-3 md:h-svh md:flex-col md:items-stretch md:gap-4 md:sticky md:top-0">
          <Link href="/" className="flex items-center gap-2 px-1 font-semibold tracking-tight">
            <span className="grid size-7 place-items-center rounded-md bg-primary text-xs font-bold text-primary-foreground">R</span>
            Renova
          </Link>
          <OrganisationSwitcher />
          <nav className="flex gap-1 overflow-x-auto md:flex-col">
            {NAV.map(({ href, label, icon: Icon }) => {
              const active = href === "/" ? pathname === "/" : pathname.startsWith(href);
              return (
                <Link
                  key={href}
                  href={href}
                  className={cn(
                    "flex items-center gap-2 rounded-md px-2.5 py-1.5 text-sm text-muted-foreground transition-colors hover:bg-muted hover:text-foreground",
                    active && "bg-muted font-medium text-foreground",
                  )}
                >
                  <Icon className="size-4" />
                  <span className="hidden sm:inline">{label}</span>
                </Link>
              );
            })}
          </nav>
          <div className="flex items-center gap-1 md:mt-auto">
            <UserMenu />
            <ThemeToggle />
          </div>
        </div>
      </aside>
      <main className="min-w-0 flex-1">
        <div className="mx-auto w-full max-w-6xl px-4 py-6 md:px-8 md:py-8">{children}</div>
      </main>
    </div>
  );
}

function OrganisationSwitcher() {
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
            <Button variant="outline" className="hidden h-auto justify-between gap-2 py-1.5 md:flex">
              <span className="flex min-w-0 items-center gap-2">
                <Building2 className="size-4 shrink-0 text-muted-foreground" />
                <span className="min-w-0 text-left">
                  <span className="block truncate text-sm">{current.name}</span>
                  <span className="block text-xs font-normal text-muted-foreground capitalize">{current.role.toLowerCase()}</span>
                </span>
              </span>
              <ChevronsUpDown className="size-3.5 shrink-0 text-muted-foreground" />
            </Button>
          }
        />
        <DropdownMenuContent className="w-56">
          <DropdownMenuGroup>
            <DropdownMenuLabel>Organisations</DropdownMenuLabel>
            {auth.data?.organisations.map((o) => (
              <DropdownMenuItem key={o.id} onClick={() => o.id !== current.id && switchTo.mutate(o.id)}>
                <span className={cn("truncate", o.id === current.id && "font-medium")}>{o.name}</span>
                <span className="ml-auto text-xs text-muted-foreground capitalize">{o.role.toLowerCase()}</span>
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
  const initials = user.name.split(/\s+/).map((p) => p[0]).slice(0, 2).join("").toUpperCase();
  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger
          render={
            <Button variant="ghost" className="h-auto min-w-0 flex-1 justify-start gap-2 px-1.5 py-1.5">
              <span className="grid size-7 shrink-0 place-items-center rounded-full bg-muted text-xs font-medium">{initials}</span>
              <span className="hidden min-w-0 text-left md:block">
                <span className="block truncate text-sm">{user.name}</span>
                <span className="block truncate text-xs font-normal text-muted-foreground">{user.email}</span>
              </span>
            </Button>
          }
        />
        <DropdownMenuContent className="w-56">
          <DropdownMenuItem onClick={() => setChanging(true)}>
            <KeyRound /> Change password
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem onClick={() => signOut.mutate()}>
            <LogOut /> Sign out
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
      <ChangePasswordDialog open={changing} onOpenChange={setChanging} />
    </>
  );
}

function ThemeToggle() {
  const { resolvedTheme, setTheme } = useTheme();
  return (
    <Button
      variant="ghost"
      size="icon-sm"
      aria-label="Toggle dark mode"
      onClick={() => setTheme(resolvedTheme === "dark" ? "light" : "dark")}
    >
      <Sun className="size-4 dark:hidden" />
      <Moon className="hidden size-4 dark:block" />
    </Button>
  );
}
