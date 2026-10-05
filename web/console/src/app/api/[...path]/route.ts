// Forwards /api/* to the Renova web API, so the browser only ever talks to the console's own origin and the
// API's session and CSRF cookies stay first-party. The API's address is read when the console runs
// (RENOVA_API_URL, default http://127.0.0.1:8787), so one build serves every environment.

export const dynamic = "force-dynamic";

const FORWARDED_REQUEST_HEADERS = ["accept", "content-type", "cookie", "x-xsrf-token"];
const FORWARDED_RESPONSE_HEADERS = ["content-type", "content-disposition", "cache-control"];

function apiUrl() {
  return (process.env.RENOVA_API_URL ?? "http://127.0.0.1:8787").replace(/\/+$/, "");
}

async function forward(request: Request): Promise<Response> {
  const incoming = new URL(request.url);
  const target = `${apiUrl()}${incoming.pathname}${incoming.search}`;
  const headers = new Headers();
  for (const name of FORWARDED_REQUEST_HEADERS) {
    const value = request.headers.get(name);
    if (value) headers.set(name, value);
  }
  let upstream: Response;
  try {
    upstream = await fetch(target, {
      method: request.method,
      headers,
      body: request.method === "GET" || request.method === "HEAD" ? undefined : await request.arrayBuffer(),
      redirect: "manual",
      cache: "no-store",
    });
  } catch {
    return Response.json({ error: `The Renova API at ${apiUrl()} is not reachable` }, { status: 502 });
  }
  const response = new Headers();
  for (const name of FORWARDED_RESPONSE_HEADERS) {
    const value = upstream.headers.get(name);
    if (value) response.set(name, value);
  }
  for (const cookie of upstream.headers.getSetCookie()) {
    response.append("set-cookie", cookie);
  }
  return new Response(upstream.body, { status: upstream.status, headers: response });
}

export { forward as GET, forward as POST, forward as PUT, forward as PATCH, forward as DELETE };
