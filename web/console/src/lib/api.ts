// Types and calls for the Renova web API (web/api). All requests go through the /api rewrite.

export type MigrationStatus = "QUEUED" | "RUNNING" | "PASSED" | "FAILED" | "ERROR";
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
}

export interface ProviderSettings {
  name: string;
  displayName: string;
  defaultModel: string;
  keyConfigured: boolean;
  key: string | null;
  keySource: string | null;
}

export interface Settings {
  provider: string;
  model: string | null;
  effort: string | null;
  rag: boolean;
  providers: ProviderSettings[];
  configFile: string;
}

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
  }
}

async function request<T>(path: string, init?: RequestInit, as: "json" | "text" = "json"): Promise<T> {
  const response = await fetch(`/api${path}`, {
    ...init,
    headers: init?.body ? { "Content-Type": "application/json", ...init.headers } : init?.headers,
    cache: "no-store",
  });
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
  projects: () => request<Project[]>("/projects"),
  project: (id: string) => request<Project>(`/projects/${id}`),
  addProject: (body: { name?: string; path: string }) =>
    request<Project>("/projects", { method: "POST", body: JSON.stringify(body) }),
  deleteProject: (id: string) => request<void>(`/projects/${id}`, { method: "DELETE" }),
  assessment: (id: string) => request<Assessment>(`/projects/${id}/assessment`),
  projectMigrations: (id: string) => request<Migration[]>(`/projects/${id}/migrations`),
  startMigration: (projectId: string, body: Partial<MigrationOptions> & { playbook?: string }) =>
    request<Migration>(`/projects/${projectId}/migrations`, { method: "POST", body: JSON.stringify(body) }),

  migrations: () => request<Migration[]>("/migrations"),
  migration: (id: string) => request<MigrationDetail>(`/migrations/${id}`),
  report: (id: string) => request<MigrationReport>(`/migrations/${id}/report`),
  behaviour: (id: string) => request<BehaviourReport>(`/migrations/${id}/behaviour`),
  commits: (id: string) => request<Commit[]>(`/migrations/${id}/commits`),
  diff: (id: string, hash: string) => request<string>(`/migrations/${id}/commits/${hash}/diff`, undefined, "text"),

  playbooks: () => request<Playbook[]>("/playbooks"),
  settings: () => request<Settings>("/settings"),
  updateSettings: (values: Record<string, string>) =>
    request<Settings>("/settings", { method: "PUT", body: JSON.stringify(values) }),
  setKey: (provider: string, apiKey: string) =>
    request<Settings>(`/settings/keys/${provider}`, { method: "PUT", body: JSON.stringify({ apiKey }) }),
  removeKey: (provider: string) => request<void>(`/settings/keys/${provider}`, { method: "DELETE" }),
  checkSettings: () => request<{ message: string }>("/settings/check", { method: "POST" }),
};

export const finished = (status: MigrationStatus) => status === "PASSED" || status === "FAILED" || status === "ERROR";
