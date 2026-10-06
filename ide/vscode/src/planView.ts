import * as path from "node:path";
import * as vscode from "vscode";

import { Assessment, Finding, PlanStep, STRATEGY_LABEL, findingsOf } from "./model";

type Node = { step: PlanStep } | { finding: Finding };

/** The Renova view: the plan's steps, and under each the places it applies to. */
export class PlanView implements vscode.TreeDataProvider<Node> {
  private readonly changed = new vscode.EventEmitter<void>();
  readonly onDidChangeTreeData = this.changed.event;
  private assessment: Assessment | undefined;

  show(assessment: Assessment | undefined): void {
    this.assessment = assessment;
    this.changed.fire();
  }

  getChildren(node?: Node): Node[] {
    const a = this.assessment;
    if (!a) return [];
    if (!node) return a.plan.map((step) => ({ step }));
    if ("step" in node) return findingsOf(a, node.step.rule).slice(0, 200).map((finding) => ({ finding }));
    return [];
  }

  getTreeItem(node: Node): vscode.TreeItem {
    if ("step" in node) {
      const s = node.step;
      const item = new vscode.TreeItem(`${s.order}. ${s.title}`, vscode.TreeItemCollapsibleState.Collapsed);
      item.description = `${STRATEGY_LABEL[s.strategy] ?? s.strategy} · ${s.category} · ${s.occurrences}`;
      item.tooltip = new vscode.MarkdownString(
        `**${s.title}**\n\nCategory ${s.category} · ${s.severity.toLowerCase()} · resolved by ${STRATEGY_LABEL[s.strategy] ?? s.strategy}` +
          (s.hint ? `\n\n${s.hint}` : "") +
          (s.recipes?.length ? `\n\n\`${s.recipes.join("`, `")}\`` : ""),
      );
      item.iconPath = new vscode.ThemeIcon(
        s.strategy === "manual" ? "person" : s.strategy === "ai" ? "sparkle" : "check",
        new vscode.ThemeColor(s.strategy === "manual" ? "problemsWarningIcon.foreground" : "charts.green"),
      );
      return item;
    }
    const [, file, line, evidence] = node.finding;
    const item = new vscode.TreeItem(`${file}${line > 0 ? `:${line}` : ""}`);
    item.description = evidence ?? undefined;
    item.iconPath = vscode.ThemeIcon.File;
    item.resourceUri = vscode.Uri.file(path.join(this.assessment!.project.root, file));
    item.command = { command: "renova.openFinding", title: "Open", arguments: [this.assessment!.project.root, file, line] };
    return item;
  }
}
