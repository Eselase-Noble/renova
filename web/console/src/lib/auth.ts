"use client";

import { useQuery } from "@tanstack/react-query";

import { api, atLeast, type Role } from "@/lib/api";

/** The signed-in user and their role in the current organisation. */
export function useAuth() {
  const state = useQuery({ queryKey: ["auth"], queryFn: api.authState, staleTime: 60_000 });
  const role = state.data?.organisation?.role;
  return {
    ...state,
    role,
    /** Renova is running for one person on this machine: nothing about accounts or members applies. */
    local: !!state.data?.localMode,
    can: (needed: Role) => atLeast(role, needed),
  };
}
