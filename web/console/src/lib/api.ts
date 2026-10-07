// Types and calls for the Renova web API (web/api). All requests go through the /api rewrite.

export type MigrationStatus = "QUEUED" | "RUNNING" | "PASSED" | "FAILED" | "ERROR" | "CANCELLED";
export type BehaviourStatus = "SAME" | "DIFFERENT" | "SKIPPED" | "FAILED";

export interface Project {
  id: string;
  name: string;
  path: string;
  ecosystem: string;
  playbook: string;
  createdAt: string;
}

export interface MigrationOptions {
  ai: boolean;
  rag: boolean;
  verifyBehaviour: boolean;
  skipTests: boolean;
  maxAiIterations: number;
}

export interface MigrationSummary {
  build: "PASSES" | "FAILS" | "NOT_VERIFIED";
  buildErrors: number;
  behaviour: BehaviourStatus | null;
  behaviourSummary: string | null;
  repairRounds: number;
  aiRequests: number;
  inputTokens: number;
  outputTokens: number;
  manualSteps: number;
  automationRate: number;
  findings: number;
}

export interface Migration {
  id: string;
  projectId: string;
  projectName: string;
  playbook: string;
  options: MigrationOptions;
  status: MigrationStatus;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  workspace: string;
  summary: MigrationSummary | null;
  error: string | null;
  /** Id of the member who started it. */
  startedBy: string | null;
}

export interface MigrationDetail {
  migration: Migration;
  progress: string[];
  progressTotal: number;
}

export interface PlanStep {
  order: number;
  rule: string;
  title: string;
  category: string;
  severity: "BLOCKER" | "WARNING" | "INFO";
  strategy: "recipe" | "replace" | "ai" | "manual" | "maven" | string;
  occurrences: number;
  files: string[];
  recipes?: string[];
  hint?: string;
}

/** [ruleId, file, line, evidence] */
export type Finding = [string, string, number, string | null];

export interface Assessment {
  project: { root: string; facts: Record<string, unknown>; ecosystem: string; modules: { name: string; path: string }[] };
  playbook: { id: string; name: string; version: string };
  summary: {
    findings: number;
    automationRate: number;
    byCategory: Record<string, number>;
    byStrategy: Record<string, number>;
  };
  plan: PlanStep[];
  findings: Finding[];
  warnings: string[];
}

export interface StageResult {
  stage: string;
  status: "APPLIED" | "PARTIAL" | "SKIPPED" | "FAILED";
  summary: string;
  details: string[];
}

export interface BuildError {
  file: string | null;
  line: number;
  message: string;
}

export interface MigrationReport extends Assessment {
  migration: {
    stages: StageResult[];
    verification: { success: boolean; errors: BuildError[]; log: string } | null;
    manualSteps: { order: number; rule: { id: string; title: string; fix: { hint?: string } }; files: string[] }[];
    repairRounds: number;
  };
}

export interface BehaviourExchange {
  status: number;
  error?: string;
  contentType: string | null;
  body: string;
}

export interface BehaviourResult {
  scenario: string;
  step: number;
  method: string;
  path: string;
  source: string;
  handler: string | null;
  same: boolean;
  differences: string[];
  notes: string[];
  original: BehaviourExchange;
  migrated: BehaviourExchange;
}

export interface BehaviourReport {
  status: BehaviourStatus;
  summary: string;
  original: string | null;
  migrated: string | null;
  results: BehaviourResult[];
  databases: Record<string, string[]>;
  accepted: string[];
}

export interface Commit {
  hash: string;
  message: string;
  filesChanged: number;
  insertions: number;
  deletions: number;
}

export interface Playbook {
  id: string;
  name: string;
  ecosystem: string;
  version: string;
  description: string | null;
  targets: Record<string, string>;
  rules: number;
  guards: number;
  knowledgeCards: number;
  /** An optional set of changes combined with a target, not a target on its own. */
  addon: boolean;
}

/** The migration paths a project can take, and the one Renova suggests for it. */
export interface ProjectTargets {
  current: string;
  recommended: string;
  playbooks: Playbook[];
  /** Optional add-ons that can be combined with the target, as target+addon+addon. */
  addons: Playbook[];
}

export interface ProviderSettings {
  name: string;
  displayName: string;
  defaultModel: string;
  keyConfigured: boolean;
  key: string | null;
  baseUrl: string | null;
}

export interface Settings {
  provider: string;
  model: string | null;
  effort: string | null;
  rag: boolean;
  providers: ProviderSettings[];
}

export type Role = "VIEWER" | "MEMBER" | "ADMIN" | "OWNER";
const RANK: Record<Role, number> = { VIEWER: 0, MEMBER: 1, ADMIN: 2, OWNER: 3 };
export const atLeast = (role: Role | undefined, needed: Role) => !!role && RANK[role] >= RANK[needed];

export interface UserView {
  id: string;
  email: string;
  name: string;
}

export interface Membership {
  id: string;
  name: string;
  role: Role;
}

export interface AuthState {
  setupRequired: boolean;
  user: UserView | null;
  organisation: Membership | null;
  organisations: Membership[];
  /** One person on their own machine: no sign-in and no members. */
  localMode?: boolean;
}

export interface Member {
  id: string;
  email: string;
  name: string;
  role: Role;
  joinedAt: string;
}

export interface OrganisationView {
  id: string;
  name: string;
  yourRole: Role;
  members: Member[];
}

export interface Invitation {
  id: string;
  email: string;
  role: Role;
  createdAt: string;
  expiresAt: string;
  /** Only when just created. */
  token: string | null;
}

export interface InvitationDetails {
  organisation: string;
  email: string;
  role: Role;
  accountExists: boolean;
  expiresAt: string;
}

export interface AuditEvent {
  id: string;
  at: string;
  actorId: string | null;
  actorName: string;
  /** area.verb, for example migration.started */
  action: string;
  target: string | null;
  detail: string | null;
}

/** The licence installed on the server: what it covers and until when. */
export interface LicenceInfo {
  valid: boolean;
  /** False in a development build, which migrates without a licence. */
  required: boolean;
  description: string;
  licensee: string | null;
  edition: string | null;
  ecosystems: string[];
  seats: number;
  expires: string | null;
}

export interface SystemInfo {
  version: string;
  java: string;
  ecosystems: { id: string; name: string }[];
  aiProviders: string[];
  playbooks: number;
  parallelMigrations: number;
  projectRoots: string[];
  /** Where the server keeps its records and the migrated copies. */
  dataDir: string;
  localMode: boolean;
}

export interface DirectoryEntry {
  name: string;
  path: string;
  /** Holds a build file an installed ecosystem recognises. */
  project: boolean;
}

export interface DirectoryListing {
  /** Null when listing the allowed roots. */
  path: string | null;
  parent: string | null;
  project: boolean;
  entries: DirectoryEntry[];
  truncated: boolean;
}

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
  }
}

/** The CSRF token the API sets as a cookie; it must come back in a header on every change. */
function csrfToken(): string | undefined {
  return document.cookie
    .split("; ")
    .find((c) => c.startsWith("XSRF-TOKEN="))
    ?.slice("XSRF-TOKEN=".length);
}

async function request<T>(path: string, init?: RequestInit, as: "json" | "text" = "json"): Promise<T> {
  const method = (init?.method ?? "GET").toUpperCase();
  const headers: Record<string, string> = { ...(init?.headers as Record<string, string>) };
  if (init?.body) headers["Content-Type"] = "application/json";
  if (method !== "GET" && method !== "HEAD") {
    if (!csrfToken()) await fetch("/api/auth/state", { cache: "no-store" }); // obtains the cookie
    const token = csrfToken();
    if (token) headers["X-XSRF-TOKEN"] = decodeURIComponent(token);
  }
  const response = await fetch(`/api${path}`, { ...init, headers, cache: "no-store" });
  if (response.status === 401 && typeof window !== "undefined" && !path.startsWith("/auth/")) {
    // The session ended: a full page load to the sign-in page also drops every cached response.
    // eslint-disable-next-line @next/next/no-location-assign-relative-destination
    window.location.assign(`/login?next=${encodeURIComponent(window.location.pathname)}`);
  }
  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`;
    try {
      const body = await response.json();
      message = body.error ?? message;
    } catch {
      // Not JSON: keep the status text.
    }
    throw new ApiError(message, response.status);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (as === "json" ? response.json() : response.text()) as Promise<T>;
}

export const api = {
  authState: () => request<AuthState>("/auth/state"),
  setup: (body: { organisation: string; name: string; email: string; password: string }) =>
    request<AuthState>("/auth/setup", { method: "POST", body: JSON.stringify(body) }),
  login: (email: string, password: string) =>
    request<AuthState>("/auth/login", { method: "POST", body: JSON.stringify({ email, password }) }),
  logout: () => request<void>("/auth/logout", { method: "POST" }),
  switchOrganisation: (organisationId: string) =>
    request<AuthState>("/auth/organisation", { method: "POST", body: JSON.stringify({ organisationId }) }),
  changePassword: (current: string, replacement: string) =>
    request<void>("/auth/password", { method: "POST", body: JSON.stringify({ current, replacement }) }),
  invitationDetails: (token: string) => request<InvitationDetails>(`/auth/invitations/${encodeURIComponent(token)}`),
  acceptInvitation: (token: string, name: string, password: string) =>
    request<AuthState>(`/auth/invitations/${encodeURIComponent(token)}/accept`, {
      method: "POST",
      body: JSON.stringify({ name, password }),
    }),

  organisation: () => request<OrganisationView>("/org"),
  createOrganisation: (name: string) => request<OrganisationView>("/orgs", { method: "POST", body: JSON.stringify({ name }) }),
  changeRole: (userId: string, role: Role) =>
    request<OrganisationView>(`/org/members/${userId}`, { method: "PATCH", body: JSON.stringify({ role }) }),
  removeMember: (userId: string) => request<OrganisationView | null>(`/org/members/${userId}`, { method: "DELETE" }),
  invitations: () => request<Invitation[]>("/org/invitations"),
  invite: (email: string, role: Role) =>
    request<Invitation>("/org/invitations", { method: "POST", body: JSON.stringify({ email, role }) }),
  revokeInvitation: (id: string) => request<void>(`/org/invitations/${id}`, { method: "DELETE" }),

  projects: () => request<Project[]>("/projects"),
  project: (id: string) => request<Project>(`/projects/${id}`),
  addProject: (body: { name?: string; path: string }) =>
    request<Project>("/projects", { method: "POST", body: JSON.stringify(body) }),
  projectTargets: (id: string) => request<ProjectTargets>(`/projects/${id}/playbooks`),
  retargetProject: (id: string, playbook: string) =>
    request<Project>(`/projects/${id}`, { method: "PATCH", body: JSON.stringify({ playbook }) }),
  deleteProject: (id: string) => request<void>(`/projects/${id}`, { method: "DELETE" }),
  assessment: (id: string) => request<Assessment>(`/projects/${id}/assessment`),
  projectMigrations: (id: string) => request<Migration[]>(`/projects/${id}/migrations`),
  startMigration: (projectId: string, body: Partial<MigrationOptions> & { playbook?: string }) =>
    request<Migration>(`/projects/${projectId}/migrations`, { method: "POST", body: JSON.stringify(body) }),

  migrations: () => request<Migration[]>("/migrations"),
  migration: (id: string) => request<MigrationDetail>(`/migrations/${id}`),
  cancelMigration: (id: string) => request<Migration>(`/migrations/${id}/cancel`, { method: "POST" }),
  report: (id: string) => request<MigrationReport>(`/migrations/${id}/report`),
  behaviour: (id: string) => request<BehaviourReport>(`/migrations/${id}/behaviour`),
  commits: (id: string) => request<Commit[]>(`/migrations/${id}/commits`),
  diff: (id: string, hash: string) => request<string>(`/migrations/${id}/commits/${hash}/diff`, undefined, "text"),

  audit: (area?: string) => request<AuditEvent[]>(`/org/audit?limit=500${area ? `&area=${encodeURIComponent(area)}` : ""}`),
  system: () => request<SystemInfo>("/system"),
  licence: () => request<LicenceInfo>("/system/licence"),
  directories: (path?: string | null) =>
    request<DirectoryListing>(`/system/directories${path ? `?path=${encodeURIComponent(path)}` : ""}`),

  playbooks: () => request<Playbook[]>("/playbooks"),
  settings: () => request<Settings>("/settings"),
  updateSettings: (values: Record<string, string>) =>
    request<Settings>("/settings", { method: "PUT", body: JSON.stringify(values) }),
  setKey: (provider: string, apiKey: string) =>
    request<Settings>(`/settings/keys/${provider}`, { method: "PUT", body: JSON.stringify({ apiKey }) }),
  removeKey: (provider: string) => request<void>(`/settings/keys/${provider}`, { method: "DELETE" }),
  checkSettings: () => request<{ message: string }>("/settings/check", { method: "POST" }),
};

export const finished = (status: MigrationStatus) => status !== "QUEUED" && status !== "RUNNING";
export const active = (status: MigrationStatus) => !finished(status);
