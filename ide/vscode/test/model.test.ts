import * as assert from "node:assert/strict";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import { test } from "node:test";

import { locate, run } from "../src/cli";
import { Assessment, migrationPaths, parseAssessment, problems, progressLine, summaryText } from "../src/model";

const sample: Assessment = {
  schema: "renova/report/v1",
  project: { root: "/work/app" },
  playbook: { id: "java8-to-21-jakarta-ee10", name: "Java 8 → 21", version: "0.1.0" },
  summary: { findings: 3, automationRate: 0.67, byCategory: { B: 2, D: 1 }, byStrategy: { recipe: 2, manual: 1 } },
  plan: [
    { order: 1, rule: "javax-ee-imports", title: "Rename javax.* Java EE packages to jakarta.*", category: "B", severity: "BLOCKER", strategy: "recipe", occurrences: 2, files: ["src/A.java"] },
    { order: 2, rule: "spring-mvc-url-matching", title: "Preserve Spring MVC URL matching", category: "D", severity: "BLOCKER", strategy: "manual", occurrences: 1, files: ["pom.xml"], hint: "Register an AntPathMatcher." },
  ],
  findings: [
    ["javax-ee-imports", "src/A.java", 3, "import javax.servlet.http.HttpServlet;"],
    ["javax-ee-imports", "src/B.java", 0, null],
    ["spring-mvc-url-matching", "pom.xml", 12, "spring-webmvc 4.3"],
  ],
  warnings: [],
};

test("findings become problems worded like the IntelliJ plugin, at 0-based lines", () => {
  const p = problems(sample);
  assert.equal(p.length, 3);
  assert.deepEqual(
    { file: p[0].file, line: p[0].line, blocker: p[0].blocker, rule: p[0].rule },
    { file: "src/A.java", line: 2, blocker: true, rule: "javax-ee-imports" },
  );
  assert.equal(
    p[0].message,
    "Renova (B): Rename javax.* Java EE packages to jakarta.*. Renova fixes this automatically. [import javax.servlet.http.HttpServlet;]",
  );
  assert.equal(p[1].line, 0, "a finding without a line goes to the first line");
  assert.match(p[2].message, /For a person/);
});

test("the summary and progress lines read as the CLI prints them", () => {
  assert.equal(summaryText(sample), "3 findings · 67% automated · 1 for a person");
  assert.equal(progressLine("» Stage recipe: 6 step(s)"), "Stage recipe: 6 step(s)");
  assert.equal(progressLine("  recipe     APPLIED  5 recipe(s)"), undefined);
});

test("the end of a migration names the workspace and the report", () => {
  const stderr = [
    "  build      PASSES",
    "Workspace: /tmp/app-renova-1  (git log for per-stage commits)",
    "Report:    /tmp/app-renova-1/.renova/report.md",
  ].join("\n");
  assert.deepEqual(migrationPaths(stderr), {
    workspace: "/tmp/app-renova-1",
    report: "/tmp/app-renova-1/.renova/report.md",
  });
});

test("other JSON is rejected", () => {
  assert.throws(() => parseAssessment('{"hello": 1}'), /Unexpected output/);
});

// Runs the real CLI when it has been built (mvn package in the repository root).
const jar = path.resolve(__dirname, "../../../../cli/target/renova.jar");
const app = path.join(os.homedir(), "Projects/renova-test-apps/claims-portal");
test("the real CLI's assessment parses", { skip: !fs.existsSync(jar) || !fs.existsSync(app) }, async () => {
  const cli = locate("", jar, "/nowhere");
  const result = await run(cli, ["analyze", app, "-f", "json"]);
  assert.equal(result.code, 0, result.stderr);
  const a = parseAssessment(result.stdout);
  assert.equal(a.project.root, app);
  const p = problems(a);
  assert.ok(p.length > 0);
  assert.ok(p.some((x) => x.file.endsWith("TokenCodec.java") && x.message.includes("sun.misc")), JSON.stringify(p.slice(0, 5)));
});
