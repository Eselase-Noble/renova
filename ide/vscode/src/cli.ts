import { spawn } from "node:child_process";
import * as fs from "node:fs";
import * as path from "node:path";

/** Where to find Java and the Renova CLI jar, from settings or defaults. */
export interface CliLocation {
  java: string;
  jar: string;
}

export interface CliResult {
  code: number;
  stdout: string;
  stderr: string;
}

export function locate(javaSetting: string, jarSetting: string, extensionDir: string): CliLocation {
  const java =
    javaSetting ||
    (process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, "bin", process.platform === "win32" ? "java.exe" : "java") : "java");
  const jar = jarSetting || path.join(extensionDir, "bin", "renova.jar");
  if (!fs.existsSync(jar)) {
    throw new Error(
      `Renova CLI not found at ${jar}. Set "renova.cliJar" to renova.jar (build it with "mvn package" in the Renova repository).`,
    );
  }
  return { java, jar };
}

/**
 * Runs the Renova CLI. stderr carries progress ("» …" lines), passed to onLine as they arrive; stdout carries
 * reports. An abort signal stops the process.
 */
export function run(
  cli: CliLocation,
  args: string[],
  options: { cwd?: string; onLine?: (line: string) => void; stdin?: string; signal?: AbortSignal } = {},
): Promise<CliResult> {
  return new Promise((resolve, reject) => {
    const child = spawn(cli.java, ["-jar", cli.jar, ...args], { cwd: options.cwd, signal: options.signal });
    let stdout = "";
    let stderr = "";
    let pending = "";
    child.stdout.on("data", (d: Buffer) => (stdout += d.toString()));
    child.stderr.on("data", (d: Buffer) => {
      const text = d.toString();
      stderr += text;
      pending += text;
      const lines = pending.split("\n");
      pending = lines.pop() ?? "";
      lines.forEach((l) => options.onLine?.(l));
    });
    child.on("error", (e: NodeJS.ErrnoException) =>
      reject(
        e.code === "ENOENT"
          ? new Error(`Java not found ("${cli.java}"). Install Java 21 or later, or set "renova.java".`)
          : e,
      ),
    );
    child.on("close", (code) => {
      if (pending) options.onLine?.(pending);
      resolve({ code: code ?? -1, stdout, stderr });
    });
    if (options.stdin !== undefined) {
      child.stdin.end(options.stdin);
    } else {
      child.stdin.end();
    }
  });
}

/** The major Java version, e.g. 21, from "java -version". */
export async function javaVersion(java: string): Promise<number> {
  const result = await new Promise<CliResult>((resolve, reject) => {
    const child = spawn(java, ["-version"]);
    let stderr = "";
    child.stderr.on("data", (d: Buffer) => (stderr += d.toString()));
    child.on("error", reject);
    child.on("close", (code) => resolve({ code: code ?? -1, stdout: "", stderr }));
  });
  const m = result.stderr.match(/version "(\d+)(?:\.(\d+))?/);
  if (!m) return 0;
  return m[1] === "1" ? Number(m[2]) : Number(m[1]);
}
