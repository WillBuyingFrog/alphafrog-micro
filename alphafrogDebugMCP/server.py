#!/usr/bin/env python3
import asyncio
import asyncpg
import os
import re
import shlex
import sys
import time
from pathlib import Path
from typing import List, Optional, Tuple

from dotenv import load_dotenv
from mcp.server.fastmcp import FastMCP

mcp = FastMCP("alphafrog-debug-mcp")

_HOST_RE = re.compile(r"^[A-Za-z0-9._-]+$")


def _load_env() -> None:
    dotenv_path = os.getenv("ALPHAFROG_DEBUG_DOTENV_PATH")
    if not dotenv_path:
        # default to repo root .env (alphafrogDebugMCP is in repo root)
        dotenv_path = str(Path(__file__).resolve().parent.parent / ".env")
    if Path(dotenv_path).exists():
        load_dotenv(dotenv_path=dotenv_path, override=False)


_load_env()


def _env_list(key: str) -> List[str]:
    raw = os.getenv(key, "").strip()
    if not raw:
        return []
    return [item.strip() for item in raw.split(",") if item.strip()]


def _resolve_env_to_host(env: str) -> Tuple[Optional[str], Optional[str]]:
    """根据 test/prod 解析 SSH host。返回 (host, error)，error 非空表示失败（面向调用方的泛化文案）。"""
    if env not in ("test", "prod"):
        return None, "env 必须为 test 或 prod"
    key = f"ALPHAFROG_DEBUG_SSH_HOST_{env.upper()}"
    resolved = os.getenv(key, "").strip()
    if not resolved:
        if env == "test":
            return None, "测试环境远程访问尚未在服务端配置完成"
        return None, "生产环境远程访问尚未在服务端配置完成"
    if not _HOST_RE.match(resolved):
        return None, "服务端远程主机配置格式无效"
    allowed = _env_list("ALPHAFROG_DEBUG_SSH_HOSTS")
    if allowed and resolved not in allowed:
        return None, "远程主机不在服务端允许列表中"
    return resolved, None


def _repo_path_for_env(env: str, repo_path_override: Optional[str]) -> Tuple[Optional[str], Optional[str]]:
    """远程仓库路径：显式参数优先，否则按环境变量与默认回退。"""
    if repo_path_override and repo_path_override.strip():
        return repo_path_override.strip(), None
    if env == "test":
        p = os.getenv("ALPHAFROG_DEBUG_REPO_PATH_TEST", "").strip()
    else:
        p = os.getenv("ALPHAFROG_DEBUG_REPO_PATH_PROD", "").strip()
    if not p:
        p = os.getenv("ALPHAFROG_DEBUG_DEFAULT_REPO_PATH", "").strip()
    if not p:
        return None, "远程仓库路径尚未在服务端配置完成"
    return p, None


def _ssh_base_args(host: str) -> List[str]:
    args = ["ssh"]
    ssh_config = os.getenv("ALPHAFROG_DEBUG_SSH_CONFIG")
    if ssh_config:
        args.extend(["-F", ssh_config])
    ssh_args = os.getenv("ALPHAFROG_DEBUG_SSH_ARGS", "").strip()
    if ssh_args:
        args.extend(shlex.split(ssh_args))
    args.append(host)
    return args


def _docker_cmd() -> List[str]:
    return shlex.split(os.getenv("ALPHAFROG_DEBUG_DOCKER_CMD", "docker"))


def _git_cmd() -> List[str]:
    return shlex.split(os.getenv("ALPHAFROG_DEBUG_GIT_CMD", "git"))


def _clamp_int(value: Optional[int], default: int, minimum: int, maximum: int) -> int:
    if value is None:
        return default
    try:
        value_int = int(value)
    except (TypeError, ValueError):
        return default
    return max(minimum, min(maximum, value_int))


def _truncate_bytes(data: bytes, max_bytes: Optional[int]) -> Tuple[bytes, bool]:
    if max_bytes is None or max_bytes <= 0:
        return data, False
    if len(data) <= max_bytes:
        return data, False
    return data[: max_bytes], True


def _filter_output(text: str, grep: Optional[str]) -> str:
    if not grep:
        return text
    lines = text.splitlines()
    if grep.startswith("re:"):
        pattern = re.compile(grep[3:])
        lines = [line for line in lines if pattern.search(line)]
    else:
        lines = [line for line in lines if grep in line]
    return "\n".join(lines)


def _redact_ssh_tool_result(result: dict, host: str) -> dict:
    """移除或脱敏可能暴露 SSH 目标的字段，再返回给 MCP 调用方。"""
    out = dict(result)
    out.pop("command", None)
    # stderr 中偶发含 Host 名，做一次简单替换
    if host and result.get("stderr"):
        out["stderr"] = result["stderr"].replace(host, "[远程主机已隐藏]")
    if host and result.get("stdout"):
        out["stdout"] = result["stdout"].replace(host, "[远程主机已隐藏]")
    return out


async def _run_ssh(
    host: str,
    remote_args: List[str],
    timeout_seconds: Optional[int] = None,
    max_bytes: Optional[int] = None,
) -> dict:
    # SSH joins multi-arg remote commands with spaces without re-quoting,
    # so args with spaces/special chars break. Use shlex.join to produce a
    # single properly-quoted shell command string for the remote shell.
    cmd = _ssh_base_args(host) + [shlex.join(remote_args)]
    start = time.monotonic()
    proc = await asyncio.create_subprocess_exec(
        *cmd, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE
    )
    timed_out = False
    try:
        if timeout_seconds:
            stdout, stderr = await asyncio.wait_for(proc.communicate(), timeout=timeout_seconds)
        else:
            stdout, stderr = await proc.communicate()
    except asyncio.TimeoutError:
        timed_out = True
        proc.kill()
        stdout, stderr = await proc.communicate()
    duration_ms = int((time.monotonic() - start) * 1000)
    stdout_truncated = False
    stderr_truncated = False
    if max_bytes is not None:
        stdout, stdout_truncated = _truncate_bytes(stdout, max_bytes)
        stderr, stderr_truncated = _truncate_bytes(stderr, max_bytes)
    return {
        "ok": proc.returncode == 0 and not timed_out,
        "exit_code": proc.returncode,
        "timed_out": timed_out,
        "duration_ms": duration_ms,
        "command": cmd,
        "stdout": stdout.decode("utf-8", errors="replace"),
        "stderr": stderr.decode("utf-8", errors="replace"),
        "stdout_truncated": stdout_truncated,
        "stderr_truncated": stderr_truncated,
    }


@mcp.tool()
async def remote_docker_ps(env: str) -> dict:
    """List running docker containers on the remote host (compact output).

    Args:
        env: Target environment. Must be "test" or "prod".

    Returns:
        dict with: ok, exit_code, duration_ms, items (list of dicts with name/image/status/ports), count;
        or ok False and error.
    """
    resolved, err = _resolve_env_to_host(env)
    if err:
        return {"ok": False, "error": err}
    # 不用 table 前缀：table 模式输出对齐空格而非 tab，无法可靠分割。
    # 直接用模板输出真实 tab 分隔符，每行即一条记录（无 header 行）。
    format_arg = "{{.Names}}\\t{{.Image}}\\t{{.Status}}\\t{{.Ports}}"
    result = await _run_ssh(resolved, _docker_cmd() + ["ps", "--format", format_arg])

    items = []
    if result["stdout"]:
        lines = result["stdout"].splitlines()
        for line in lines:
            if not line.strip():
                continue
            parts = line.split("\t")
            if len(parts) >= 4:
                items.append({
                    "name": parts[0].strip(),
                    "image": parts[1].strip(),
                    "status": parts[2].strip(),
                    "ports": parts[3].strip(),
                })
            elif len(parts) >= 1:
                items.append({"name": parts[0].strip(), "image": "", "status": "", "ports": ""})

    # 不返回原始 stdout（已经解析为 items，stdout 是冗余的）
    return {
        "ok": result["ok"],
        "exit_code": result["exit_code"],
        "duration_ms": result["duration_ms"],
        "items": items,
        "count": len(items),
    }


@mcp.tool()
async def remote_git_log(
    env: str,
    repo_path: Optional[str] = None,
    limit: Optional[int] = 10,
) -> dict:
    """Show recent git log on the remote host.

    Args:
        env: Target environment. Must be "test" or "prod".
        repo_path: optional remote repo path (overrides server-side path for this env).
        limit: max commits to return (clamped 1..200).

    Returns:
        dict with ok/exit_code/timed_out/duration_ms/stdout/stderr（不含 command）.
    """
    resolved, err = _resolve_env_to_host(env)
    if err:
        return {"ok": False, "error": err}
    repo, repo_err = _repo_path_for_env(env, repo_path)
    if repo_err:
        return {"ok": False, "error": repo_err}
    limit_val = _clamp_int(limit, 10, 1, 200)
    remote_args = _git_cmd() + ["-C", repo, "log", f"-n{limit_val}", "--oneline", "--decorate"]
    raw = await _run_ssh(resolved, remote_args)
    return _redact_ssh_tool_result(raw, resolved)


@mcp.tool()
async def remote_docker_logs(
    env: str,
    container: str = "",
    tail: Optional[int] = 200,
    grep: Optional[str] = None,
    timestamps: bool = True,
    max_bytes: Optional[int] = 20000,
    timeout_seconds: Optional[int] = 30,
) -> dict:
    """Fetch docker logs on the remote host (non-follow).

    Args:
        env: Target environment. Must be "test" or "prod".
        container: container name/id (required).
        tail: number of lines from the end of the log (clamped 1..10000, default 200).
        grep: substring filter, or regex via 're:<pattern>'.
        timestamps: include timestamps in output (default true).
        max_bytes: truncate stdout/stderr to this many bytes.
        timeout_seconds: overall timeout for command.

    Returns:
        dict with ok/exit_code/timed_out/duration_ms/stdout/stderr（不含 command）.
    """
    if not container:
        return {"ok": False, "error": "container 参数不能为空"}
    resolved, err = _resolve_env_to_host(env)
    if err:
        return {"ok": False, "error": err}
    tail_val = _clamp_int(tail, 200, 1, 10000)
    args = _docker_cmd() + ["logs", f"--tail={tail_val}"]
    if timestamps:
        args.append("--timestamps")
    args.append(container)
    result = await _run_ssh(resolved, args, timeout_seconds=timeout_seconds, max_bytes=max_bytes)
    if grep:
        result["stdout"] = _filter_output(result["stdout"], grep)
    return _redact_ssh_tool_result(result, resolved)


@mcp.tool()
async def remote_docker_follow(
    env: str,
    container: str = "",
    follow_seconds: Optional[int] = 15,
    tail: Optional[int] = 200,
    grep: Optional[str] = None,
    timestamps: bool = True,
    max_bytes: Optional[int] = 50000,
) -> dict:
    """Follow docker logs on the remote host for a limited time.

    Args:
        env: Target environment. Must be "test" or "prod".
        container: container name/id (required).
        follow_seconds: follow duration (clamped 1..300, default 15).
        tail: number of lines from the end of log shown before following (clamped 1..10000, default 200).
        grep: substring filter, or regex via 're:<pattern>'.
        timestamps: include timestamps in output (default true).
        max_bytes: truncate stdout/stderr to this many bytes.

    Returns:
        dict with ok/exit_code/timed_out/duration_ms/stdout/stderr（不含 command）.
    """
    if not container:
        return {"ok": False, "error": "container 参数不能为空"}
    resolved, err = _resolve_env_to_host(env)
    if err:
        return {"ok": False, "error": err}
    follow_val = _clamp_int(follow_seconds, 15, 1, 300)
    tail_val = _clamp_int(tail, 200, 1, 10000)
    args = _docker_cmd() + ["logs", "-f", f"--tail={tail_val}"]
    if timestamps:
        args.append("--timestamps")
    args.append(container)
    result = await _run_ssh(resolved, args, timeout_seconds=follow_val, max_bytes=max_bytes)
    if grep:
        result["stdout"] = _filter_output(result["stdout"], grep)
    return _redact_ssh_tool_result(result, resolved)


_ALLOWED_TABLE_PREFIX = "alphafrog_"
_DANGEROUS_KEYWORDS = re.compile(
    r'\b(INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|TRUNCATE|GRANT|REVOKE|EXEC|EXECUTE|COPY|VACUUM|MERGE)\b',
    re.IGNORECASE,
)
_TABLE_REF_RE = re.compile(r'\b(?:FROM|JOIN)\s+([a-zA-Z_][a-zA-Z0-9_]*)', re.IGNORECASE)
_LIMIT_RE = re.compile(r'\bLIMIT\s+\d+\b', re.IGNORECASE)
_MAX_ROWS = 100


def _validate_sql(sql: str) -> Optional[str]:
    """返回 None 表示通过，返回字符串为拒绝原因。"""
    stripped = sql.strip()
    if not stripped.upper().startswith("SELECT"):
        return "Only SELECT statements are allowed"
    if _DANGEROUS_KEYWORDS.search(stripped):
        return "Dangerous keyword detected"
    tables = _TABLE_REF_RE.findall(stripped)
    for t in tables:
        if not t.lower().startswith(_ALLOWED_TABLE_PREFIX):
            return f"Table '{t}' is not allowed (must start with 'alphafrog_')"
    return None


def _pg_config_error_message(env: str) -> str:
    if env == "test":
        return "所选测试环境尚未在服务端完成数据库连接配置"
    return "所选生产环境尚未在服务端完成数据库连接配置"


@mcp.tool()
async def remote_pg_query(
    env: str,
    sql: str,
) -> dict:
    """Execute a read-only SELECT query against the alphafrog PostgreSQL database.

    Args:
        env: Target environment. Must be "test" or "prod".
        sql: A SELECT statement. Only alphafrog_* tables are allowed. Max 100 rows returned.

    Returns:
        dict with: ok, columns, rows (list of lists), row_count, truncated.
    """
    if env not in ("test", "prod"):
        return {"ok": False, "error": "env 必须为 test 或 prod"}

    rejection = _validate_sql(sql)
    if rejection:
        return {"ok": False, "error": rejection}

    dsn_key = f"ALPHAFROG_PG_{env.upper()}_DSN"
    dsn = os.getenv(dsn_key)
    if not dsn:
        return {"ok": False, "error": _pg_config_error_message(env)}

    # 强制替换或追加 LIMIT，防止内层查询拉取大量行
    safe_sql = _LIMIT_RE.sub("", sql.rstrip().rstrip(";")).rstrip()
    safe_sql = f"{safe_sql} LIMIT {_MAX_ROWS}"

    try:
        conn = await asyncpg.connect(dsn)
        try:
            records = await conn.fetch(safe_sql)
            columns = list(records[0].keys()) if records else []
            rows = [list(r.values()) for r in records]
            return {
                "ok": True,
                "columns": columns,
                "rows": rows,
                "row_count": len(rows),
                "truncated": len(rows) >= _MAX_ROWS,
            }
        finally:
            await conn.close()
    except Exception as e:
        print(f"[remote_pg_query] {e!r}", file=sys.stderr)
        return {"ok": False, "error": "数据库查询执行失败，详情请查看 MCP 服务端日志"}


if __name__ == "__main__":
    mcp.run()
