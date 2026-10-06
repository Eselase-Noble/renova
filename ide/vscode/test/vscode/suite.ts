// Inside VS Code: assess the opened test app and check what VS Code shows.
import * as assert from "node:assert/strict";
import * as vscode from "vscode";

export async function run(): Promise<void> {
  const jar = process.env.RENOVA_CLI_JAR;
  if (jar) {
    await vscode.workspace.getConfiguration("renova").update("cliJar", jar, vscode.ConfigurationTarget.Global);
  }
  const extension = vscode.extensions.all.find((e) => e.packageJSON.name === "renova");
  assert.ok(extension, "the Renova extension is installed");
  await extension.activate();

  await vscode.commands.executeCommand("renova.assess");

  const all = vscode.languages.getDiagnostics().filter(([, list]) => list.some((d) => d.source === "Renova"));
  const count = all.reduce((n, [, list]) => n + list.length, 0);
  console.log(`Renova diagnostics: ${count} in ${all.length} files`);
  assert.ok(count > 10, `expected findings, got ${count}`);
  const codec = all.find(([uri]) => uri.path.endsWith("TokenCodec.java"));
  assert.ok(codec, "TokenCodec.java has findings");
  assert.ok(codec[1].some((d) => d.message.includes("sun.misc") && d.message.startsWith("Renova (C)")), "the sun.misc finding is shown");
  console.log(codec[1].map((d) => `  line ${d.range.start.line + 1}: ${d.message}`).join("\n"));
}
