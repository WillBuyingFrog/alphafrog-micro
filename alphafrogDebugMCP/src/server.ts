/**
 * alphafrog-debug-mcp — stdio MCP，与历史 Python server.py 行为对齐。
 * 禁止向 stdout 写入日志（会破坏 JSON-RPC），仅使用 stderr。
 */
import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import dotenv from "dotenv";
import pg from "pg";
import { parse as parseShell, quote } from "shell-quote";
import { z } from "zod";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// ---------- 环境加载（与 Python 一致：默认仓库根 .env）----------
function loadEnv(): void {
  const custom = process.env.ALPHAFROG_DEBUG_DOTENV_PATH?.trim();
  const repoRoot = path.resolve(__dirname, "..", "..");
  const dotenvPath = custom && existsSync(custom) ? custom : path.join(repoRoot, ".env");
  if (existsSync(dotenvPath)) {
    dotenv.config({ path: dotenvPath });
  }
}

loadEnv();

const HOST_RE = /^[A-Za-z0-9._-]+$/;

function envList(key: string): string[] {
  const raw = (process.env[key] ?? "").trim();
  if (!raw) return [];
  return raw.split(",").map((s) => s.trim()).filter(Boolean);
}

function resolveEnvToHost(env: string): { host: string } | { error: string } {
  if (env !== "test" && env !== "prod") {
    return { error: "env 必须为 test 或 prod" };
  }
  const key = `ALPHAFROG_DEBUG_SSH_HOST_${env.toUpperCase()}`;
  const resolved = (process.env[key] ?? "").trim();
  if (!resolved) {
    return {
      error:
        env === "test"
          ? "测试环境远程访问尚未在服务端配置完成"
          : "生产环境远程访问尚未在服务端配置完成",
    };
  }
  if (!HOST_RE.test(resolved)) {
    return { error: "服务端远程主机配置格式无效" };
  }
  const allowed = envList("ALPHAFROG_DEBUG_SSH_HOSTS");
  if (allowed.length > 0 && !allowed.includes(resolved)) {
    return { error: "远程主机不在服务端允许列表中" };
  }
  return { host: resolved };
}

function repoPathForEnv(
  env: string,
  repoPathOverride: string | null | undefined
): { path: string } | { error: string } {
  if (repoPathOverride?.trim()) {
    return { path: repoPathOverride.trim() };
  }
  let p =
    env === "test"
      ? (process.env.ALPHAFROG_DEBUG_REPO_PATH_TEST ?? "").trim()
      : (process.env.ALPHAFROG_DEBUG_REPO_PATH_PROD ?? "").trim();
  if (!p) {
    p = (process.env.ALPHAFROG_DEBUG_DEFAULT_REPO_PATH ?? "").trim();
  }
  if (!p) {
    return { error: "远程仓库路径尚未在服务端配置完成" };
  }
  return { path: p };
}

function parseShellArgs(raw: string): string[] {
  const tokens = parseShell(raw) as Array<string | { op: string }>;
  return tokens.flatMap((t) => (typeof t === "string" ? [t] : []));
}

function sshBaseArgs(host: string): string[] {
  const args: string[] = ["ssh"];
  const sshConfig = process.env.ALPHAFROG_DEBUG_SSH_CONFIG?.trim();
  if (sshConfig) {
    args.push("-F", sshConfig);
  }
  const extra = process.env.ALPHAFROG_DEBUG_SSH_ARGS?.trim();
  if (extra) {
    args.push(...parseShellArgs(extra));
  }
  args.push(host);
  return args;
}

function dockerCmd(): string[] {
  return parseShellArgs(process.env.ALPHAFROG_DEBUG_DOCKER_CMD ?? "docker");
}

function gitCmd(): string[] {
  return parseShellArgs(process.env.ALPHAFROG_DEBUG_GIT_CMD ?? "git");
}

function clampInt(
  value: number | null | undefined,
  defaultVal: number,
  min: number,
  max: number
): number {
  if (value === null || value === undefined) return defaultVal;
  const n = Number(value);
  if (!Number.isFinite(n)) return defaultVal;
  return Math.min(max, Math.max(min, Math.floor(n)));
}

function truncateBytes(buf: Buffer, maxBytes: number | null | undefined): [Buffer, boolean] {
  if (maxBytes === null || maxBytes === undefined || maxBytes <= 0) {
    return [buf, false];
  }
  if (buf.length <= maxBytes) return [buf, false];
  return [buf.subarray(0, maxBytes), true];
}

function filterOutput(text: string, grep: string | null | undefined): string {
  if (!grep) return text;
  const lines = text.split(/\r?\n/);
  if (grep.startsWith("re:")) {
    const pattern = new RegExp(grep.slice(3));
    return lines.filter((line) => pattern.test(line)).join("\n");
  }
  return lines.filter((line) => line.includes(grep)).join("\n");
}

type SshRunResult = {
  ok: boolean;
  exit_code: number;
  timed_out: boolean;
  duration_ms: number;
  command: string[];
  stdout: string;
  stderr: string;
  stdout_truncated: boolean;
  stderr_truncated: boolean;
};

function redactSshToolResult(result: SshRunResult, host: string): Omit<SshRunResult, "command"> {
  const { command: _c, ...rest } = result;
  const out: Omit<SshRunResult, "command"> = { ...rest };
  if (host && result.stderr) {
    out.stderr = result.stderr.split(host).join("[远程主机已隐藏]");
  }
  if (host && result.stdout) {
    out.stdout = result.stdout.split(host).join("[远程主机已隐藏]");
  }
  return out;
}

async function runSsh(
  host: string,
  remoteArgs: string[],
  options: { timeoutSeconds?: number | null; maxBytes?: number | null } = {}
): Promise<SshRunResult> {
  const remoteJoined = quote(remoteArgs);
  const cmd = [...sshBaseArgs(host), remoteJoined];
  const start = performance.now();
  const child = spawn(cmd[0], cmd.slice(1), {
    stdio: ["ignore", "pipe", "pipe"],
  });

  const timeoutMs =
    options.timeoutSeconds !== null && options.timeoutSeconds !== undefined && options.timeoutSeconds > 0
      ? options.timeoutSeconds * 1000
      : undefined;

  let timedOut = false;
  let timeoutId: ReturnType<typeof setTimeout> | undefined;

  const stdoutChunks: Buffer[] = [];
  const stderrChunks: Buffer[] = [];

  const stdoutPromise = new Promise<void>((resolve) => {
    child.stdout?.on("data", (d: Buffer) => stdoutChunks.push(d));
    child.stdout?.on("end", () => resolve());
  });
  const stderrPromise = new Promise<void>((resolve) => {
    child.stderr?.on("data", (d: Buffer) => stderrChunks.push(d));
    child.stderr?.on("end", () => resolve());
  });

  if (timeoutMs !== undefined) {
    timeoutId = setTimeout(() => {
      timedOut = true;
      child.kill("SIGKILL");
    }, timeoutMs);
  }

  const exitCode = await new Promise<number>((resolve) => {
    child.on("exit", (code) => resolve(code ?? 1));
    child.on("error", () => resolve(1));
  });

  if (timeoutId) clearTimeout(timeoutId);
  await Promise.all([stdoutPromise, stderrPromise]);

  let stdoutBuf = Buffer.concat(stdoutChunks);
  let stderrBuf = Buffer.concat(stderrChunks);
  let stdoutTruncated = false;
  let stderrTruncated = false;
  let maxB = options.maxBytes;
  if (maxB !== null && maxB !== undefined && maxB > 0) {
    const [o, ot] = truncateBytes(Buffer.from(stdoutBuf), maxB);
    const [e, et] = truncateBytes(Buffer.from(stderrBuf), maxB);
    stdoutBuf = Buffer.from(o);
    stderrBuf = Buffer.from(e);
    stdoutTruncated = ot;
    stderrTruncated = et;
  }

  const duration_ms = Math.round(performance.now() - start);

  return {
    ok: exitCode === 0 && !timedOut,
    exit_code: exitCode,
    timed_out: timedOut,
    duration_ms,
    command: cmd,
    stdout: stdoutBuf.toString("utf8"),
    stderr: stderrBuf.toString("utf8"),
    stdout_truncated: stdoutTruncated,
    stderr_truncated: stderrTruncated,
  };
}

// ---------- SQL 校验（与 Python 一致）----------
const ALLOWED_TABLE_PREFIX = "alphafrog_";
const DANGEROUS_KEYWORDS =
  /\b(INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|TRUNCATE|GRANT|REVOKE|EXEC|EXECUTE|COPY|VACUUM|MERGE)\b/i;
const TABLE_REF_RE = /\b(?:FROM|JOIN)\s+([a-zA-Z_][a-zA-Z0-9_]*)/gi;
const LIMIT_RE = /\bLIMIT\s+\d+\b/gi;
const MAX_ROWS = 100;

function validateSql(sql: string): string | null {
  const stripped = sql.trim();
  if (!stripped.toUpperCase().startsWith("SELECT")) {
    return "Only SELECT statements are allowed";
  }
  if (DANGEROUS_KEYWORDS.test(stripped)) {
    return "Dangerous keyword detected";
  }
  const tables = [...stripped.matchAll(TABLE_REF_RE)].map((m) => m[1]);
  for (const t of tables) {
    if (!t.toLowerCase().startsWith(ALLOWED_TABLE_PREFIX)) {
      return `Table '${t}' is not allowed (must start with 'alphafrog_')`;
    }
  }
  return null;
}

function pgConfigErrorMessage(env: string): string {
  return env === "test"
    ? "所选测试环境尚未在服务端完成数据库连接配置"
    : "所选生产环境尚未在服务端完成数据库连接配置";
}

function toolJson(data: Record<string, unknown>) {
  return {
    content: [{ type: "text" as const, text: JSON.stringify(data) }],
    structuredContent: data,
  };
}

// ---------- MCP 注册 ----------
const server = new McpServer({
  name: "alphafrog-debug-mcp",
  version: "1.0.0",
});

const envSchema = z.enum(["test", "prod"]);

server.registerTool(
  "remote_docker_ps",
  {
    description: `List running docker containers on the remote host (compact output).
env: Target environment. Must be "test" or "prod".`,
    inputSchema: {
      env: envSchema,
    },
  },
  async ({ env }) => {
    const resolved = resolveEnvToHost(env);
    if ("error" in resolved) {
      return toolJson({ ok: false, error: resolved.error });
    }
    const formatArg = "{{.Names}}\\t{{.Image}}\\t{{.Status}}\\t{{.Ports}}";
    const result = await runSsh(resolved.host, [...dockerCmd(), "ps", "--format", formatArg]);

    const items: Array<{ name: string; image: string; status: string; ports: string }> = [];
    if (result.stdout) {
      for (const line of result.stdout.split(/\r?\n/)) {
        if (!line.trim()) continue;
        const parts = line.split("\t");
        if (parts.length >= 4) {
          items.push({
            name: parts[0].trim(),
            image: parts[1].trim(),
            status: parts[2].trim(),
            ports: parts[3].trim(),
          });
        } else if (parts.length >= 1) {
          items.push({ name: parts[0].trim(), image: "", status: "", ports: "" });
        }
      }
    }

    return toolJson({
      ok: result.ok,
      exit_code: result.exit_code,
      duration_ms: result.duration_ms,
      items,
      count: items.length,
    });
  }
);

server.registerTool(
  "remote_git_log",
  {
    description: `Show recent git log on the remote host.`,
    inputSchema: {
      env: envSchema,
      repo_path: z.string().optional().nullable(),
      limit: z.number().int().optional().nullable(),
    },
  },
  async ({ env, repo_path, limit }) => {
    const resolved = resolveEnvToHost(env);
    if ("error" in resolved) {
      return toolJson({ ok: false, error: resolved.error });
    }
    const rp = repoPathForEnv(env, repo_path);
    if ("error" in rp) {
      return toolJson({ ok: false, error: rp.error });
    }
    const limitVal = clampInt(limit, 10, 1, 200);
    const remoteArgs = [
      ...gitCmd(),
      "-C",
      rp.path,
      "log",
      `-n${limitVal}`,
      "--oneline",
      "--decorate",
    ];
    const raw = await runSsh(resolved.host, remoteArgs);
    return toolJson(redactSshToolResult(raw, resolved.host) as unknown as Record<string, unknown>);
  }
);

server.registerTool(
  "remote_docker_logs",
  {
    description: `Fetch docker logs on the remote host (non-follow).`,
    inputSchema: {
      env: envSchema,
      container: z.string().default(""),
      tail: z.number().int().optional().nullable(),
      grep: z.string().optional().nullable(),
      timestamps: z.boolean().optional().nullable(),
      max_bytes: z.number().int().optional().nullable(),
      timeout_seconds: z.number().int().optional().nullable(),
    },
  },
  async ({ env, container, tail, grep, timestamps, max_bytes, timeout_seconds }) => {
    if (!container?.trim()) {
      return toolJson({ ok: false, error: "container 参数不能为空" });
    }
    const resolved = resolveEnvToHost(env);
    if ("error" in resolved) {
      return toolJson({ ok: false, error: resolved.error });
    }
    const tailVal = clampInt(tail, 200, 1, 10000);
    const ts = timestamps ?? true;
    const args = [...dockerCmd(), "logs", `--tail=${tailVal}`];
    if (ts) args.push("--timestamps");
    args.push(container.trim());
    let result = await runSsh(resolved.host, args, {
      timeoutSeconds: timeout_seconds ?? 30,
      maxBytes: max_bytes ?? 20000,
    });
    if (grep) {
      result = { ...result, stdout: filterOutput(result.stdout, grep) };
    }
    return toolJson(redactSshToolResult(result, resolved.host) as unknown as Record<string, unknown>);
  }
);

server.registerTool(
  "remote_docker_follow",
  {
    description: `Follow docker logs on the remote host for a limited time.`,
    inputSchema: {
      env: envSchema,
      container: z.string().default(""),
      follow_seconds: z.number().int().optional().nullable(),
      tail: z.number().int().optional().nullable(),
      grep: z.string().optional().nullable(),
      timestamps: z.boolean().optional().nullable(),
      max_bytes: z.number().int().optional().nullable(),
    },
  },
  async ({ env, container, follow_seconds, tail, grep, timestamps, max_bytes }) => {
    if (!container?.trim()) {
      return toolJson({ ok: false, error: "container 参数不能为空" });
    }
    const resolved = resolveEnvToHost(env);
    if ("error" in resolved) {
      return toolJson({ ok: false, error: resolved.error });
    }
    const followVal = clampInt(follow_seconds, 15, 1, 300);
    const tailVal = clampInt(tail, 200, 1, 10000);
    const ts = timestamps ?? true;
    const args = [...dockerCmd(), "logs", "-f", `--tail=${tailVal}`];
    if (ts) args.push("--timestamps");
    args.push(container.trim());
    let result = await runSsh(resolved.host, args, {
      timeoutSeconds: followVal,
      maxBytes: max_bytes ?? 50000,
    });
    if (grep) {
      result = { ...result, stdout: filterOutput(result.stdout, grep) };
    }
    return toolJson(redactSshToolResult(result, resolved.host) as unknown as Record<string, unknown>);
  }
);

server.registerTool(
  "remote_pg_query",
  {
    description: `Execute a read-only SELECT query against the alphafrog PostgreSQL database.`,
    inputSchema: {
      env: envSchema,
      sql: z.string(),
    },
  },
  async ({ env, sql }) => {
    if (env !== "test" && env !== "prod") {
      return toolJson({ ok: false, error: "env 必须为 test 或 prod" });
    }
    const rejection = validateSql(sql);
    if (rejection) {
      return toolJson({ ok: false, error: rejection });
    }
    const dsnKey = `ALPHAFROG_PG_${env.toUpperCase()}_DSN`;
    const dsn = process.env[dsnKey];
    if (!dsn) {
      return toolJson({ ok: false, error: pgConfigErrorMessage(env) });
    }

    let safeSql = sql.trim().replace(/;+\s*$/, "").trim();
    safeSql = safeSql.replace(LIMIT_RE, "").trim();
    safeSql = `${safeSql} LIMIT ${MAX_ROWS}`;

    const client = new pg.Client({ connectionString: dsn });
    try {
      await client.connect();
      const res = await client.query(safeSql);
      const columns = res.fields.map((f) => f.name);
      const rows = res.rows.map((row) => columns.map((c) => row[c]));
      return toolJson({
        ok: true,
        columns,
        rows,
        row_count: rows.length,
        truncated: rows.length >= MAX_ROWS,
      });
    } catch (e) {
      console.error("[remote_pg_query]", e);
      return toolJson({
        ok: false,
        error: "数据库查询执行失败，详情请查看 MCP 服务端日志",
      });
    } finally {
      await client.end().catch(() => {});
    }
  }
);

async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
  console.error("alphafrog-debug-mcp (Node) running on stdio");
}

main().catch((err) => {
  console.error("Server error:", err);
  process.exit(1);
});
