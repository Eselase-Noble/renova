// The Renova CLI's JSON assessment (renova analyze -f json) and what the extension shows of it. No VS Code
// dependency, so it is unit-tested with plain Node.

export interface PlanStep {
  order: number;
  rule: string;
  title: string;
  category: string;
  severity: "BLOCKER" | "WARNING" | "INFO";
  strategy: string;
  occurrences: number;
  files: string[];
  recipes?: string[];
  hint?: string;
}

/** [ruleId, file, line, evidence] */
export type Finding = [string, string, number, string | null];

export interface Assessment {
  schema: string;
  project: { root: string };
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

export interface Problem {
  /** Relative to the project root. */
  file: string;
  /** 0-based; the first line when the finding has no line. */
  line: number;
  message: string;
  /** Blockers are warnings; the rest are hints. */
  blocker: boolean;
  rule: string;
}

const RESOLUTION: Record<string, string> = {
  recipe: "Renova fixes this automatically",
  replace: "Renova fixes this automatically",
  maven: "Renova fixes this automatically",
  gradle: "Renova fixes this automatically",
  dotnet: "Renova fixes this automatically",
  "dotnet-source": "Renova fixes this automatically",
  ai: "Renova fixes this with AI, checked by the build",
  manual: "For a person: see the guidance in the Renova view",
};

export const STRATEGY_LABEL: Record<string, string> = {
  recipe: "recipe",
  replace: "text rule",
  maven: "build file edit",
  gradle: "build file edit",
  dotnet: "project file edit",
  "dotnet-source": "source rewrite",
  ai: "AI",
  manual: "for a person",
};

export function parseAssessment(json: string): Assessment {
  const a = JSON.parse(json) as Assessment;
  if (a.schema !== "renova/report/v1" || !Array.isArray(a.plan) || !Array.isArray(a.findings)) {
    throw new Error("Unexpected output from the Renova CLI");
  }
  return a;
}

/** One problem per finding, worded like the IntelliJ plugin: category, title, and who resolves it. */
export function problems(a: Assessment): Problem[] {
  const steps = new Map(a.plan.map((s) => [s.rule, s]));
  return a.findings
    .filter(([, file]) => !!file)
    .map(([rule, file, line, evidence]) => {
      const step = steps.get(rule);
      const resolution = step ? RESOLUTION[step.strategy] ?? `Strategy ${step.strategy}` : "";
      const title = step?.title ?? rule;
      return {
        file,
        line: Math.max(0, line - 1),
        message: `Renova (${step?.category ?? "?"}): ${title}${resolution ? `. ${resolution}.` : ""}${evidence ? ` [${evidence}]` : ""}`,
        blocker: step?.severity === "BLOCKER",
        rule,
      };
    });
}

/** "36 findings · 86% automated · 1 for a person" */
export function summaryText(a: Assessment): string {
  const manual = a.plan.filter((s) => s.strategy === "manual").length;
  return `${a.summary.findings} findings · ${Math.round(a.summary.automationRate * 100)}% automated · ${manual} for a person`;
}

/** The findings of one plan step, for the tree view. */
export function findingsOf(a: Assessment, rule: string): Finding[] {
  return a.findings.filter(([r]) => r === rule);
}

/** Progress lines the CLI prints while migrating ("» Stage recipe: 6 step(s)"), without the marker. */
export function progressLine(line: string): string | undefined {
  const m = line.match(/^» (.*)$/);
  return m ? m[1] : undefined;
}

/** The migrated workspace and report paths printed at the end of "renova migrate". */
export function migrationPaths(output: string): { workspace?: string; report?: string } {
  const workspace = output.match(/^Workspace:\s+(.+?)\s{2}\(/m)?.[1] ?? output.match(/^Workspace:\s+(\S+)/m)?.[1];
  const report = output.match(/^Report:\s+(.+)$/m)?.[1]?.trim();
  return { workspace, report };
}
