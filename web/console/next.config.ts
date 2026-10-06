import type { NextConfig } from "next";

// Calls to /api/* are forwarded to the Renova web API by src/app/api/[...path]/route.ts, which reads
// RENOVA_API_URL when the console runs.
const nextConfig: NextConfig = {
  // A self-contained server (.next/standalone) that runs with Node alone: what web/package.sh ships.
  output: "standalone",
};

export default nextConfig;
