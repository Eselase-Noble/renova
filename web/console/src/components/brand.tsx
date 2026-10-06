import { cn } from "@/lib/utils";

/** The Renova mark: two chevrons rising out of a base line, the old system lifted onto a new platform. */
export function LogoMark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 32 32" className={cn("size-8", className)} role="img" aria-label="Renova">
      <defs>
        <linearGradient id="renova-mark" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="oklch(0.62 0.19 268)" />
          <stop offset="1" stopColor="oklch(0.45 0.21 276)" />
        </linearGradient>
      </defs>
      <rect width="32" height="32" rx="8" fill="url(#renova-mark)" />
      <path d="M9 17.5 16 11l7 6.5" fill="none" stroke="#fff" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M9 23.5 16 17l7 6.5" fill="none" stroke="#fff" strokeOpacity="0.55" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

export function Wordmark({ className }: { className?: string }) {
  return <span className={cn("text-[15px] font-semibold tracking-tight", className)}>Renova</span>;
}
