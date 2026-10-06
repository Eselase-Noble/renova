"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { use, useState } from "react";

import { AuthCard, Field } from "@/components/auth-card";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { api } from "@/lib/api";

export default function InvitePage({ params }: { params: Promise<{ token: string }> }) {
  const { token } = use(params);
  const details = useQuery({ queryKey: ["invitation", token], queryFn: () => api.invitationDetails(token), retry: false });
  const [name, setName] = useState("");
  const [password, setPassword] = useState("");
  const router = useRouter();
  const queryClient = useQueryClient();
  const accept = useMutation({
    mutationFn: () => api.acceptInvitation(token, name, password),
    onSuccess: (state) => {
      queryClient.clear();
      queryClient.setQueryData(["auth"], state);
      router.replace("/");
    },
  });

  if (details.error) {
    return (
      <AuthCard title="Invitation not valid">
        <p className="text-sm text-muted-foreground">{details.error.message}. Ask the person who invited you for a new link.</p>
      </AuthCard>
    );
  }
  if (!details.data) {
    return (
      <AuthCard title="Checking your invitation">
        <Skeleton className="h-40 w-full" />
      </AuthCard>
    );
  }
  const d = details.data;

  return (
    <AuthCard
      title={`Join ${d.organisation}`}
      description={<>You are invited as <span className="font-medium lowercase">{d.role}</span>, as {d.email}.</>}
    >
      <form className="space-y-4" onSubmit={(e) => { e.preventDefault(); accept.mutate(); }}>
        {!d.accountExists && (
          <Field id="name" label="Your name" autoComplete="name" required autoFocus value={name} onChange={(e) => setName(e.target.value)} />
        )}
        <Field
          id="password"
          label={d.accountExists ? "Your password" : "Choose a password"}
          type="password"
          autoComplete={d.accountExists ? "current-password" : "new-password"}
          minLength={d.accountExists ? undefined : 10}
          required
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          hint={d.accountExists ? "You already have an account; sign in to join." : "At least 10 characters."}
        />
        {accept.error && (
          <Alert variant="destructive">
            <AlertDescription>{accept.error.message}</AlertDescription>
          </Alert>
        )}
        <Button type="submit" size="lg" className="w-full" disabled={accept.isPending}>
          {accept.isPending ? "Joining…" : `Join ${d.organisation}`}
        </Button>
      </form>
    </AuthCard>
  );
}
