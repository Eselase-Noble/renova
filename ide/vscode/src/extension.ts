import * as path from "node:path";
import * as vscode from "vscode";

import { CliLocation, javaVersion, locate, run } from "./cli";
import { Assessment, migrationPaths, parseAssessment, problems, progressLine, summaryText } from "./model";
import { PlanView } from "./planView";

let diagnostics: vscode.DiagnosticCollection;
let output: vscode.OutputChannel;
let status: vscode.StatusBarItem;
let plan: PlanView;
let busy = false;

export function activate(context: vscode.ExtensionContext): void {
  diagnostics = vscode.languages.createDiagnosticCollection("renova");
  output = vscode.window.createOutputChannel("Renova");
  status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 10);
  status.command = "renova.assess";
  status.text = "$(sync) Renova";
  status.tooltip = "Assess this workspace with Renova";
  status.show();
  plan = new PlanView();
  context.subscriptions.push(
    diagnostics,
    output,
    status,
    vscode.window.registerTreeDataProvider("renovaPlan", plan),
    vscode.commands.registerCommand("renova.assess", () => assess(context)),
    vscode.commands.registerCommand("renova.migrate", () => migrate(context)),
    vscode.commands.registerCommand("renova.chooseProvider", () => chooseProvider(context)),
    vscode.commands.registerCommand("renova.setKey", () => setKey(context)),
    vscode.commands.registerCommand("renova.clear", () => {
      diagnostics.clear();
      plan.show(undefined);
      status.text = "$(sync) Renova";
    }),
    vscode.commands.registerCommand("renova.openFinding", openFinding),
  );
  if (vscode.workspace.getConfiguration("renova").get<boolean>("assessOnOpen")) {
    void assess(context);
  }
}

export function deactivate(): void {
  // Nothing to release beyond the subscriptions.
}

function root(): string | undefined {
  return vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
}

async function cli(context: vscode.ExtensionContext): Promise<CliLocation> {
  const config = vscode.workspace.getConfiguration("renova");
  const location = locate(config.get<string>("java") ?? "", config.get<string>("cliJar") ?? "", context.extensionPath);
  const version = await javaVersion(location.java).catch(() => 0);
  if (version < 21) {
    throw new Error(
      version === 0
        ? `Java not found ("${location.java}"). Install Java 21 or later, or set "renova.java".`
        : `Renova needs Java 21 or later; "${location.java}" is Java ${version}. Set "renova.java".`,
    );
  }
  return location;
}

async function assess(context: vscode.ExtensionContext): Promise<Assessment | undefined> {
  const dir = root();
  if (!dir) {
    void vscode.window.showWarningMessage("Renova: open a project folder first.");
    return undefined;
  }
  if (busy) return undefined;
  busy = true;
  try {
    return await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Window, title: "Renova: assessing" },
      async () => {
        status.text = "$(sync~spin) Renova: assessing…";
        const result = await run(await cli(context), ["analyze", dir, "-f", "json"], { cwd: dir });
        if (result.code !== 0) {
          throw new Error(result.stderr.trim() || `renova analyze exited with ${result.code}`);
        }
        const a = parseAssessment(result.stdout);
        show(a);
        return a;
      },
    );
  } catch (e) {
    status.text = "$(warning) Renova";
    void vscode.window.showErrorMessage(`Renova: ${(e as Error).message}`);
    return undefined;
  } finally {
    busy = false;
  }
}

function show(a: Assessment): void {
  const byFile = new Map<string, vscode.Diagnostic[]>();
  for (const p of problems(a)) {
    const d = new vscode.Diagnostic(
      new vscode.Range(p.line, 0, p.line, Number.MAX_SAFE_INTEGER),
      p.message,
      p.blocker ? vscode.DiagnosticSeverity.Warning : vscode.DiagnosticSeverity.Information,
    );
    d.source = "Renova";
    d.code = p.rule;
    const list = byFile.get(p.file) ?? [];
    list.push(d);
    byFile.set(p.file, list);
  }
  diagnostics.clear();
  byFile.forEach((list, file) => diagnostics.set(vscode.Uri.file(path.join(a.project.root, file)), list));
  plan.show(a);
  status.text = `$(check) Renova: ${summaryText(a)}`;
  status.tooltip = `${a.playbook.name}\nClick to assess again`;
}

async function openFinding(projectRoot: string, file: string, line: number): Promise<void> {
  const doc = await vscode.workspace.openTextDocument(vscode.Uri.file(path.join(projectRoot, file)));
  const position = new vscode.Position(Math.max(0, line - 1), 0);
  await vscode.window.showTextDocument(doc, { selection: new vscode.Range(position, position) });
}

async function migrate(context: vscode.ExtensionContext): Promise<void> {
  const dir = root();
  if (!dir) {
    void vscode.window.showWarningMessage("Renova: open a project folder first.");
    return;
  }
  const options = await vscode.window.showQuickPick(
    [
      { label: "Use AI", description: "Judgement calls and repair, with the provider set in Renova (your own key)", id: "ai", picked: true },
      { label: "Run the project's tests", description: "Code that compiles can still fail at runtime", id: "tests", picked: true },
      { label: "Verify behaviour", description: "Run the original and the migrated app side by side (Docker)", id: "behaviour", picked: false },
    ],
    { canPickMany: true, title: "Migrate with Renova", placeHolder: "Choose options, then Enter" },
  );
  if (!options) return;
  const chosen = new Set(options.map((o) => o.id));
  const stamp = new Date().toISOString().replace(/[-:]/g, "").replace("T", "-").slice(0, 15);
  const out = await vscode.window.showInputBox({
    title: "Folder for the migrated copy",
    prompt: "A new or empty folder. Renova never changes this project; each stage is a commit in the copy.",
    value: `${dir}-renova-${stamp}`,
  });
  if (!out) return;

  const args = ["migrate", dir, "--out", out];
  if (!chosen.has("ai")) args.push("--skip", "ai");
  if (!chosen.has("tests")) args.push("--skip-tests");
  if (chosen.has("behaviour")) args.push("--verify-behaviour");

  output.show(true);
  output.appendLine(`renova ${args.join(" ")}`);
  const abort = new AbortController();
  let result;
  try {
    result = await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: "Renova: migrating", cancellable: true },
      async (progress, token) => {
        token.onCancellationRequested(() => abort.abort());
        return run(await cli(context), args, {
          cwd: dir,
          signal: abort.signal,
          onLine: (line) => {
            output.appendLine(line);
            const step = progressLine(line);
            if (step) progress.report({ message: step });
          },
        });
      },
    );
  } catch (e) {
    void vscode.window.showErrorMessage(`Renova: ${abort.signal.aborted ? "migration cancelled" : (e as Error).message}`);
    return;
  }
  const { workspace, report } = migrationPaths(result.stderr);
  const message =
    result.code === 0
      ? "Renova: migration passed (build, tests and, if checked, behaviour)."
      : result.code === 1
        ? "Renova: migration finished with problems. See the report."
        : `Renova: migration stopped (${result.stderr.trim().split("\n").pop()})`;
  const actions = [report ? "Open Report" : undefined, workspace ? "Open Migrated Copy" : undefined].filter(
    (a): a is string => !!a,
  );
  const pick = await (result.code === 0
    ? vscode.window.showInformationMessage(message, ...actions)
    : vscode.window.showWarningMessage(message, ...actions));
  if (pick === "Open Report" && report) {
    await vscode.commands.executeCommand("markdown.showPreview", vscode.Uri.file(report));
  } else if (pick === "Open Migrated Copy" && workspace) {
    await vscode.commands.executeCommand("vscode.openFolder", vscode.Uri.file(workspace), { forceNewWindow: true });
  }
}

async function chooseProvider(context: vscode.ExtensionContext): Promise<void> {
  const pick = await vscode.window.showQuickPick(
    [
      { label: "Anthropic Claude", id: "anthropic" },
      { label: "OpenAI (or an OpenAI-compatible endpoint)", id: "openai" },
      { label: "No AI", description: "AI steps are listed for a person", id: "none" },
    ],
    { title: "Renova: AI provider (saved in the Renova user config, shared with the CLI)" },
  );
  if (!pick) return;
  const result = await run(await cli(context), ["config", "set", "ai.provider", pick.id]);
  void (result.code === 0
    ? vscode.window.showInformationMessage(`Renova: AI provider set to ${pick.label}.`)
    : vscode.window.showErrorMessage(`Renova: ${result.stderr.trim()}`));
}

async function setKey(context: vscode.ExtensionContext): Promise<void> {
  const provider = await vscode.window.showQuickPick(
    [
      { label: "Anthropic Claude", id: "anthropic" },
      { label: "OpenAI (or an OpenAI-compatible endpoint)", id: "openai" },
    ],
    { title: "Renova: API key for" },
  );
  if (!provider) return;
  const key = await vscode.window.showInputBox({
    title: `Your ${provider.label} API key`,
    prompt: "Saved in the Renova user config (~/.config/renova), readable only by you. Never sent anywhere but the provider.",
    password: true,
    ignoreFocusOut: true,
  });
  if (!key) return;
  // The key goes through stdin, never on a command line.
  const result = await run(await cli(context), ["config", "set-key", provider.id], { stdin: `${key}\n` });
  void (result.code === 0
    ? vscode.window.showInformationMessage(`Renova: ${provider.label} key saved.`)
    : vscode.window.showErrorMessage(`Renova: ${result.stderr.trim()}`));
}
