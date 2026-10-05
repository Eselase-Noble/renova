"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";

import { AuthCard, Field } from "@/components/auth-card";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth";

export default function SetupPage() {
  const [form, setForm] = useState({ organisation: "", name: "", email: "", password: "" });
  const set = (key: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [key]: e.target.value });
  const router = useRouter();
  const queryClient = useQueryClient();
  const auth = useAuth();

  useEffect(() => {
    if (auth.data && !auth.data.setupRequired) router.replace(auth.data.user ? "/" : "/login");
  }, [auth.data, router]);

  const setup = useMutation({
    mutationFn: () => api.setup(form),
    onSuccess: (state) => {
      queryClient.clear();
      queryClient.setQueryData(["auth"], state);
      router.replace("/settings");
    },
  });

  return (
    <AuthCard
      title="Set up Renova"
      description="Create the first account and your organisation. Others join by invitation."
    >
      <form className="space-y-4" onSubmit={(e) => { e.preventDefault(); setup.mutate(); }}>
        <Field id="organisation" label="Organisation" required autoFocus value={form.organisation} onChange={set("organisation")} placeholder="Acme Ltd" />
        <Field id="name" label="Your name" autoComplete="name" required value={form.name} onChange={set("name")} />
        <Field id="email" label="Email" type="email" autoComplete="email" required value={form.email} onChange={set("email")} />
        <Field
          id="password"
          label="Password"
          type="password"
          autoComplete="new-password"
          minLength={10}
          required
          value={form.password}
          onChange={set("password")}
          hint="At least 10 characters."
        />
        {setup.error && (
          <Alert variant="destructive">
            <AlertDescription>{setup.error.message}</AlertDescription>
          </Alert>
        )}
        <Button type="submit" className="w-full" disabled={setup.isPending}>
          {setup.isPending ? "Setting up…" : "Create account and organisation"}
        </Button>
      </form>
    </AuthCard>
  );
}
