// Runs the extension inside a real VS Code (downloaded on first use) against a Renova test app.
import * as os from "node:os";
import * as path from "node:path";
import { runTests } from "@vscode/test-electron";

async function main(): Promise<void> {
  const extensionDevelopmentPath = path.resolve(__dirname, "../../..");
  const extensionTestsPath = path.resolve(__dirname, "suite");
  const workspace = process.env.RENOVA_TEST_APP ?? path.join(os.homedir(), "Projects/renova-test-apps/claims-portal");
  await runTests({
    extensionDevelopmentPath,
    extensionTestsPath,
    launchArgs: [workspace, "--disable-extensions", "--disable-workspace-trust"],
    extensionTestsEnv: {
      RENOVA_CLI_JAR: path.resolve(extensionDevelopmentPath, "../../cli/target/renova.jar"),
    },
  });
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
