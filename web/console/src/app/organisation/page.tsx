"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, Copy, Minus, UserPlus, X } from "lucide-react";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { toast } from "sonner";

import { Avatar, ErrorState, LoadingRows, PageHeader, Panel } from "@/components/page";
import { ToneBadge } from "@/components/status";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { api, type Invitation, type Role } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { ago, dateTime, plural } from "@/lib/format";

const ROLES: Record<Role, string> = { VIEWER: "Viewer", MEMBER: "Member", ADMIN: "Admin", OWNER: "Owner" };
const ROLE_ORDER: Role[] = ["VIEWER", "MEMBER", "ADMIN", "OWNER"];
/** What each role may do, as the least role that allows it. */
const PERMISSIONS: [string, Role][] = [
  ["See projects, assessments, migrations and reports", "VIEWER"],
  ["Start and cancel migrations; check the AI key", "MEMBER"],
  ["Add and remove projects", "ADMIN"],
  ["Set the AI provider and keys", "ADMIN"],
  ["Invite, remove and change the role of members; read the audit log", "ADMIN"],
  ["Grant or remove the owner role", "OWNER"],
];

export default function OrganisationPage() {
  const auth = useAuth();
  const org = useQuery({ queryKey: ["organisation"], queryFn: api.organisation });
  const queryClient = useQueryClient();
  const router = useRouter();
  const isAdmin = auth.can("ADMIN");
  const isOwner = auth.can("OWNER");
  const me = auth.data?.user?.id;

  const changeRole = useMutation({
    mutationFn: ({ id, role }: { id: string; role: Role }) => api.changeRole(id, role),
    onSuccess: (data) => {
      queryClient.setQueryData(["organisation"], data);
      queryClient.invalidateQueries({ queryKey: ["auth"] });
      toast.success("Role changed");
    },
    onError: (e) => toast.error(e.message),
  });
  const remove = useMutation({
    mutationFn: (id: string) => api.removeMember(id),
    onSuccess: (_, id) => {
      if (id === me) {
        queryClient.clear();
        router.replace("/");
        return;
      }
      queryClient.invalidateQueries({ queryKey: ["organisation"] });
      toast.success("Member removed");
    },
    onError: (e) => toast.error(e.message),
  });

  if (org.error) return <ErrorState error={org.error} />;
  if (!org.data) return <LoadingRows rows={4} />;
  const roles = Object.entries(ROLES).filter(([r]) => isOwner || r !== "OWNER") as [Role, string][];
  const lastOwner = org.data.members.filter((m) => m.role === "OWNER").length === 1;

  return (
    <>
      <PageHeader
        title="Members"
        description={`People in ${org.data.name} and what they may do. You are ${/^[AO]/.test(ROLES[org.data.yourRole]) ? "an" : "a"} ${ROLES[org.data.yourRole].toLowerCase()}.`}
      />
      <div className="space-y-6">
        <Panel title="Members" description={plural(org.data.members.length, "person", "people")} bodyClassName="p-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Member</TableHead>
                <TableHead>Role</TableHead>
                <TableHead className="hidden md:table-cell">Joined</TableHead>
                <TableHead />
              </TableRow>
            </TableHeader>
            <TableBody>
              {org.data.members.map((m) => {
                const editable = isAdmin && m.id !== me && (isOwner || m.role !== "OWNER");
                return (
                  <TableRow key={m.id} className="hover:bg-muted/30">
                    <TableCell>
                      <span className="flex items-center gap-3">
                        <Avatar name={m.name} className="size-8 text-xs" />
                        <span className="min-w-0">
                          <span className="block truncate font-medium">
                            {m.name} {m.id === me && <span className="text-xs font-normal text-muted-foreground">(you)</span>}
                          </span>
                          <span className="block truncate text-xs text-muted-foreground">{m.email}</span>
                        </span>
                      </span>
                    </TableCell>
                    <TableCell>
                      {editable ? (
                        <Select value={m.role} onValueChange={(v) => v && changeRole.mutate({ id: m.id, role: v as Role })} items={ROLES}>
                          <SelectTrigger size="sm" className="w-28">
                            <SelectValue />
                          </SelectTrigger>
                          <SelectContent>
                            {roles.map(([value, label]) => (
                              <SelectItem key={value} value={value}>{label}</SelectItem>
                            ))}
                          </SelectContent>
                        </Select>
                      ) : (
                        <ToneBadge tone={m.role === "OWNER" ? "brand" : "muted"}>{ROLES[m.role]}</ToneBadge>
                      )}
                    </TableCell>
                    <TableCell className="hidden text-muted-foreground md:table-cell" title={dateTime(m.joinedAt)}>{ago(m.joinedAt)}</TableCell>
                    <TableCell className="text-right">
                      {(editable || (m.id === me && !(m.role === "OWNER" && lastOwner))) && (
                        <Button variant="ghost" size="sm" onClick={() => remove.mutate(m.id)} disabled={remove.isPending}>
                          {m.id === me ? "Leave" : "Remove"}
                        </Button>
                      )}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </Panel>
        {isAdmin && <Invitations roles={roles} />}
        <Panel title="Roles" description="Each role includes everything the roles before it allow." bodyClassName="p-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Permission</TableHead>
                {ROLE_ORDER.map((r) => (
                  <TableHead key={r} className="w-20 text-center">{ROLES[r]}</TableHead>
                ))}
              </TableRow>
            </TableHeader>
            <TableBody>
              {PERMISSIONS.map(([permission, least]) => (
                <TableRow key={permission} className="hover:bg-transparent">
                  <TableCell className="whitespace-normal">{permission}</TableCell>
                  {ROLE_ORDER.map((r) => {
                    const allowed = ROLE_ORDER.indexOf(r) >= ROLE_ORDER.indexOf(least);
                    return (
                      <TableCell key={r} className="text-center">
                        {allowed ? (
                          <Check className="mx-auto size-4 text-success" aria-label="Allowed" />
                        ) : (
                          <Minus className="mx-auto size-4 text-muted-foreground/40" aria-label="Not allowed" />
                        )}
                      </TableCell>
                    );
                  })}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Panel>
      </div>
    </>
  );
}

function Invitations({ roles }: { roles: [Role, string][] }) {
  const invitations = useQuery({ queryKey: ["invitations"], queryFn: api.invitations });
  const [email, setEmail] = useState("");
  const [role, setRole] = useState<Role>("MEMBER");
  const [created, setCreated] = useState<Invitation | null>(null);
  const queryClient = useQueryClient();
  const invite = useMutation({
    mutationFn: () => api.invite(email, role),
    onSuccess: (inv) => {
      setCreated(inv);
      setEmail("");
      queryClient.invalidateQueries({ queryKey: ["invitations"] });
    },
    onError: (e) => toast.error(e.message),
  });
  const revoke = useMutation({
    mutationFn: api.revokeInvitation,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["invitations"] }),
    onError: (e) => toast.error(e.message),
  });
  const items = Object.fromEntries(roles);

  return (
    <Panel title="Invitations" description="Invite someone with a link. Links work once and expire after 7 days." bodyClassName="p-5 space-y-4">
        <form className="flex flex-col gap-2 sm:flex-row sm:items-end" onSubmit={(e) => { e.preventDefault(); invite.mutate(); }}>
          <div className="flex-1 space-y-2">
            <Label htmlFor="invite-email">Email</Label>
            <Input id="invite-email" type="email" required value={email} onChange={(e) => setEmail(e.target.value)} placeholder="colleague@company.com" />
          </div>
          <div className="space-y-2">
            <Label>Role</Label>
            <Select value={role} onValueChange={(v) => v && setRole(v as Role)} items={items}>
              <SelectTrigger className="w-32">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {roles.map(([value, label]) => (
                  <SelectItem key={value} value={value}>{label}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <Button type="submit" disabled={!email || invite.isPending}>
            <UserPlus /> Invite
          </Button>
        </form>
        {created?.token && <InvitationLink invitation={created} onDone={() => setCreated(null)} />}
        {invitations.data && invitations.data.length > 0 && (
          <div className="overflow-hidden rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Email</TableHead>
                <TableHead>Role</TableHead>
                <TableHead className="hidden sm:table-cell">Expires</TableHead>
                <TableHead />
              </TableRow>
            </TableHeader>
            <TableBody>
              {invitations.data.map((i) => (
                <TableRow key={i.id}>
                  <TableCell>{i.email}</TableCell>
                  <TableCell>{ROLES[i.role]}</TableCell>
                  <TableCell className="hidden text-muted-foreground sm:table-cell" title={dateTime(i.expiresAt)}>{ago(i.expiresAt)}</TableCell>
                  <TableCell className="text-right">
                    <Button variant="ghost" size="sm" onClick={() => revoke.mutate(i.id)}>Revoke</Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          </div>
        )}
    </Panel>
  );
}

function InvitationLink({ invitation, onDone }: { invitation: Invitation; onDone: () => void }) {
  const link = `${window.location.origin}/invite/${invitation.token}`;
  const [copied, setCopied] = useState(false);
  return (
    <div className="space-y-2 rounded-lg border border-success/30 bg-success/5 p-3">
      <div className="flex items-start justify-between gap-2 text-sm">
        <span>
          Send this link to <span className="font-medium">{invitation.email}</span>. It is shown only once.
        </span>
        <Button variant="ghost" size="icon-xs" onClick={onDone} aria-label="Dismiss">
          <X />
        </Button>
      </div>
      <div className="flex gap-2">
        <Input readOnly value={link} className="font-mono text-xs" onFocus={(e) => e.target.select()} />
        <Button
          variant="secondary"
          onClick={async () => {
            await navigator.clipboard.writeText(link);
            setCopied(true);
          }}
        >
          {copied ? <Check /> : <Copy />} {copied ? "Copied" : "Copy"}
        </Button>
      </div>
    </div>
  );
}
