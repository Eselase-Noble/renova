"use client";

import { useCallback, useSyncExternalStore } from "react";

const listeners = new Set<() => void>();

function subscribe(listener: () => void) {
  listeners.add(listener);
  window.addEventListener("storage", listener);
  return () => {
    listeners.delete(listener);
    window.removeEventListener("storage", listener);
  };
}

/** A small preference kept in this browser (sidebar collapsed, list or grid), shared by every tab. */
export function useStored(key: string, fallback: string): [string, (value: string) => void] {
  const value = useSyncExternalStore(
    subscribe,
    () => window.localStorage.getItem(key) ?? fallback,
    () => fallback,
  );
  const set = useCallback(
    (next: string) => {
      window.localStorage.setItem(key, next);
      listeners.forEach((l) => l());
    },
    [key],
  );
  return [value, set];
}
