import type { NextConfig } from "next";

// The console talks to the Renova web API through this rewrite, so the browser only ever sees one origin.
const apiUrl = process.env.RENOVA_API_URL ?? "http://127.0.0.1:8787";

const nextConfig: NextConfig = {
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${apiUrl}/api/:path*` }];
  },
};

export default nextConfig;
