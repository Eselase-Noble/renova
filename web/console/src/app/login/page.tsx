"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState } from "react";

import { AuthCard, Field } from "@/components/auth-card";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button, buttonVariants } from "@/components/ui/button";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { cn } from "@/lib/utils";

export default function LoginPage() {
  return (
    <Suspense>
      <Login />
    </Suspense>
  );
}

function Login() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const router = useRouter();
  const auth = useAuth();
  const params = useSearchParams();
  const next = params.get("next");
  // Set by the API when sign-in through the identity provider was refused.
  const refused = params.get("error");
  const sso = auth.data?.sso;
  const target = next && next.startsWith("/") && !next.startsWith("//") ? next : "/";
  const queryClient = useQueryClient();

  useEffect(() => {
    if (auth.data?.setupRequired) router.replace("/setup");
    else if (auth.data?.user) router.replace(target);
  }, [auth.data, router, target]);

  const login = useMutation({
    mutationFn: () => api.login(email, password),
    onSuccess: (state) => {
      queryClient.clear();
      queryClient.setQueryData(["auth"], state);
      router.replace(target);
    },
  });

  return (
    <AuthCard title="Sign in" description="Your organisation's legacy migrations, assessed and verified.">
      {refused && (
        <Alert variant="destructive" className="mb-4">
          <AlertDescription>{refused}</AlertDescription>
        </Alert>
      )}
      {sso && (
        <div className="mb-4 space-y-4">
          <a href={sso.url} className={cn(buttonVariants({ variant: sso.only ? "default" : "outline", size: "lg" }), "w-full")}>
            Sign in with {sso.name}
          </a>
          {!sso.only && <p className="text-center text-xs text-muted-foreground">or with your Renova password</p>}
        </div>
      )}
      {!sso?.only && (
      <form className="space-y-4" onSubmit={(e) => { e.preventDefault(); login.mutate(); }}>
        <Field id="email" label="Email" type="email" autoComplete="email" required autoFocus value={email} onChange={(e) => setEmail(e.target.value)} />
        <Field id="password" label="Password" type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
        {login.error && (
          <Alert variant="destructive">
            <AlertDescription>{login.error.message}</AlertDescription>
          </Alert>
        )}
        <Button type="submit" size="lg" className="w-full" disabled={login.isPending}>
          {login.isPending ? "Signing in…" : "Sign in"}
        </Button>
        <p className="text-center text-xs text-muted-foreground">New here? Ask an admin of your organisation for an invitation.</p>
      </form>
      )}
    </AuthCard>
  );
}
