import fs from "node:fs";
import path from "node:path";
import { spawn } from "node:child_process";

import * as yaml from "js-yaml";
import { minimatch } from "minimatch";

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";

type BoxYaml = {
  commands?: Record<string, { run: string; description?: string }>;
  runtime?: { env?: Record<string, string> };
};

type PoliciesYaml = {
  mode?: "readonly" | "safe-write" | "admin";
  allow?: { commands?: string[]; read_paths?: string[]; write_paths?: string[] };
  limits?: { max_log_bytes_to_read?: number; max_runtime_seconds?: number };
};

const REPO_ROOT = process.cwd();

function readYaml<T>(p: string): T {
  const s = fs.readFileSync(p, "utf8");
  return yaml.load(s) as T;
}

function safeResolve(rel: string): string {
  const abs = path.resolve(REPO_ROOT, rel);
  // Prevent prefix-trick paths (e.g. /repo vs /repo-evil)
  if (!(abs === REPO_ROOT || abs.startsWith(REPO_ROOT + path.sep))) {
    throw new Error(`Path escapes repo root: ${rel}`);
  }
  return abs;
}

function loadBox(): BoxYaml {
  return readYaml<BoxYaml>(safeResolve(".box/box.yaml"));
}

function loadPolicies(): PoliciesYaml {
  return readYaml<PoliciesYaml>(safeResolve(".box/policies.yaml"));
}

function allowlistedCommand(cmd: string, policies: PoliciesYaml): boolean {
  const allowed = policies.allow?.commands ?? [];
  return allowed.includes(cmd);
}

function withinReadPaths(filePath: string, policies: PoliciesYaml): boolean {
  // Default-deny if policies are missing/malformed
  const allow = policies.allow?.read_paths ?? [];
  const rel = path.relative(REPO_ROOT, filePath).replaceAll("\\", "/");
  return allow.some((p) => {
    const norm = p.replaceAll("\\", "/").replace(/\/+$/, "");
    // allow "." means repo root
    if (norm === "." || norm === "") return true;
    // allow directory prefix or glob
    if (norm.includes("*")) return minimatch(rel, norm);
    return rel === norm || rel.startsWith(norm + "/");
  });
}

function runShell(command: string, env: Record<string, string>, timeoutMs: number, stdin?: string): Promise<{ code: number; stdout: string; stderr: string }> {
  return new Promise((resolve, reject) => {
    const child = spawn(command, {
      shell: true,
      cwd: REPO_ROOT,
      env: { ...process.env, ...env },
    });

    let stdout = "";
    let stderr = "";

    const killTimer = setTimeout(() => {
      child.kill("SIGKILL");
      reject(new Error(`Command timed out after ${timeoutMs}ms`));
    }, timeoutMs);

    child.stdout.on("data", (d) => (stdout += d.toString()));
    child.stderr.on("data", (d) => (stderr += d.toString()));

    child.on("close", (code) => {
      clearTimeout(killTimer);
      resolve({ code: code ?? 1, stdout, stderr });
    });

    // Write stdin if provided, then close
    if (stdin !== undefined) {
      child.stdin.write(stdin);
      child.stdin.end();
    }
  });
}

function tailFile(p: string, tailLines: number, maxBytes: number): string {
  const data = fs.readFileSync(p);
  const slice = data.slice(Math.max(0, data.length - maxBytes));
  const text = slice.toString("utf8");
  const lines = text.split(/\r?\n/);
  return lines.slice(Math.max(0, lines.length - tailLines)).join("\n");
}

function globLogs(globPattern: string): string[] {
  // minimal glob: support logs/*.log and logs/**/*.log with directory walk
  const root = safeResolve("logs");
  if (!fs.existsSync(root)) return [];
  const out: string[] = [];

  function walk(dir: string) {
    for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, ent.name);
      if (ent.isDirectory()) walk(full);
      else out.push(full);
    }
  }
  walk(root);

  return out.filter((p) => minimatch(path.relative(REPO_ROOT, p).replaceAll("\\", "/"), globPattern));
}

async function main() {
  const server = new Server(
    { name: "devbox-mcp", version: "0.1.0" },
    { capabilities: { tools: {} } }
  );

  server.setRequestHandler(ListToolsRequestSchema, async () => {
    // Source-of-truth: .box/mcp/server.json (what you already have)
    const toolSpecPath = safeResolve(".box/mcp/server.json");
    const spec = JSON.parse(fs.readFileSync(toolSpecPath, "utf8"));
    return { tools: spec.tools ?? [] };
  });

  server.setRequestHandler(CallToolRequestSchema, async (req) => {
    const tool = req.params.name;
    const input = (req.params.arguments ?? {}) as any;

    const box = loadBox();
    const policies = loadPolicies();
    const envFromBox = box.runtime?.env ?? {};
    const maxBytes = policies.limits?.max_log_bytes_to_read ?? 2_000_000;
    const timeoutMs = (policies.limits?.max_runtime_seconds ?? 900) * 1000;

    if (tool === "box-run") {
      const cmdName = String(input.command ?? "");
      if (!cmdName) throw new Error("Missing command");

      if (!allowlistedCommand(cmdName, policies)) {
        throw new Error(`Command not allowlisted by .box/policies.yaml: ${cmdName}`);
      }

      const cmd = box.commands?.[cmdName]?.run;
      if (!cmd) throw new Error(`Unknown command in .box/box.yaml: ${cmdName}`);

      const userArgs: string[] = Array.isArray(input.args) ? input.args.map(String) : [];

      // Security: Pass args via environment variables to avoid shell injection
      // Scripts can read from $BOX_ARG_0, $BOX_ARG_1, etc.
      const argsEnv = userArgs.reduce((acc, arg, i) => {
        acc[`BOX_ARG_${i}`] = arg;
        return acc;
      }, { BOX_ARG_COUNT: String(userArgs.length) } as Record<string, string>);

      const r = await runShell(cmd, { ...envFromBox, ...argsEnv }, timeoutMs);
      return {
        content: [
          { type: "text", text: `exit_code=${r.code}\n\nSTDOUT:\n${r.stdout}\n\nSTDERR:\n${r.stderr}` },
        ],
        isError: r.code !== 0,
      };
    }

    if (tool === "box-health") {
      if (!allowlistedCommand("health", policies)) {
        throw new Error(`Command not allowlisted by .box/policies.yaml: health`);
      }
      // implement via the box "health" command so it stays consistent with your scripts
      const cmd = box.commands?.["health"]?.run;
      if (!cmd) throw new Error(`Missing commands.health in .box/box.yaml`);

      const r = await runShell(cmd, envFromBox, timeoutMs);
      return {
        content: [{ type: "text", text: r.stdout || r.stderr || "" }],
        isError: r.code !== 0,
      };
    }

    if (tool === "box-read-logs") {
      const glob = String(input.glob ?? "");
      const tailLines = Math.max(1, Math.min(2000, Number(input.tailLines ?? 200)));
      const contains = input.contains ? String(input.contains) : null;

      if (!glob) throw new Error("Missing glob");

      const files = globLogs(glob);
      const chunks: string[] = [];

      for (const f of files) {
        if (!withinReadPaths(f, policies)) continue;
        let text = tailFile(f, tailLines, maxBytes);
        if (contains) {
          const lines = text.split(/\r?\n/).filter((l) => l.includes(contains));
          text = lines.join("\n");
        }
        chunks.push(`--- ${path.relative(REPO_ROOT, f)} ---\n${text}`);
      }

      return { content: [{ type: "text", text: chunks.join("\n\n") || "(no matching logs)" }] };
    }

    if (tool === "box-read-contract") {
      const rel = String(input.path ?? "");
      if (!rel) throw new Error("Missing path");

      const abs = safeResolve(rel);
      if (!withinReadPaths(abs, policies)) throw new Error("Path not allowed by policies");
      const st = fs.statSync(abs);
      if (st.size > maxBytes) {
        throw new Error(`File too large to read (${st.size} bytes > ${maxBytes} bytes)`);
      }
      const text = fs.readFileSync(abs, "utf8");
      return { content: [{ type: "text", text }] };
    }

    if (tool === "box-api-metrics") {
      if (!allowlistedCommand("api-metrics", policies)) {
        throw new Error("Command not allowlisted by .box/policies.yaml: api-metrics");
      }
      const cmd = box.commands?.["api-metrics"]?.run;
      if (!cmd) throw new Error("Missing command in .box/box.yaml: api-metrics");
      const r = await runShell(cmd, envFromBox, timeoutMs);
      return {
        content: [{ type: "text", text: r.stdout || r.stderr || "" }],
        isError: r.code !== 0,
      };
    }

    if (tool === "box-api-cluster-status") {
      if (!allowlistedCommand("api-cluster-status", policies)) {
        throw new Error("Command not allowlisted by .box/policies.yaml: api-cluster-status");
      }
      const cmd = box.commands?.["api-cluster-status"]?.run;
      if (!cmd) throw new Error("Missing command in .box/box.yaml: api-cluster-status");
      const r = await runShell(cmd, envFromBox, timeoutMs);
      return {
        content: [{ type: "text", text: r.stdout || r.stderr || "" }],
        isError: r.code !== 0,
      };
    }

    if (tool === "box-api-search") {
      if (!allowlistedCommand("api-search", policies)) {
        throw new Error("Command not allowlisted by .box/policies.yaml: api-search");
      }
      const cmd = box.commands?.["api-search"]?.run;
      if (!cmd) throw new Error("Missing command in .box/box.yaml: api-search");

      // Build JSON payload from input
      const payload: Record<string, unknown> = {
        query: input.query,
        partitionId: input.partitionId,
      };
      if (input.searchType) payload.searchType = input.searchType;
      if (input.fusionStrategy) payload.fusionStrategy = input.fusionStrategy;
      if (input.pageSize) payload.pageSize = input.pageSize;
      if (input.filters) payload.filters = input.filters;
      if (input.facets) payload.facets = input.facets;
      if (input.sortFields) payload.sortFields = input.sortFields;

      // Pass JSON via stdin to avoid shell injection
      const jsonStr = JSON.stringify(payload);
      const r = await runShell(cmd, envFromBox, timeoutMs, jsonStr);
      return {
        content: [{ type: "text", text: r.stdout || r.stderr || "" }],
        isError: r.code !== 0,
      };
    }

    if (tool === "box-api-index") {
      if (!allowlistedCommand("api-index", policies)) {
        throw new Error("Command not allowlisted by .box/policies.yaml: api-index");
      }
      const cmd = box.commands?.["api-index"]?.run;
      if (!cmd) throw new Error("Missing command in .box/box.yaml: api-index");

      // Build JSON payload from input
      const payload: Record<string, unknown> = {
        partitionId: input.partitionId,
        fields: input.fields,
      };
      if (input.id) payload.id = input.id;

      // Pass JSON via stdin to avoid shell injection
      const jsonStr = JSON.stringify(payload);
      const r = await runShell(cmd, envFromBox, timeoutMs, jsonStr);
      return {
        content: [{ type: "text", text: r.stdout || r.stderr || "" }],
        isError: r.code !== 0,
      };
    }

    throw new Error(`Unknown tool: ${tool}`);
  });

  const transport = new StdioServerTransport();
  await server.connect(transport);
}

main().catch((e) => {
  // Stdio servers should log to stderr
  console.error(String(e?.stack ?? e));
  process.exit(1);
});