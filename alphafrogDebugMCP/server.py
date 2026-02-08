#!/usr/bin/env python3
import asyncio
import json
import os
import re
import shlex
import time
from pathlib import Path
from typing import Optional, Tuple, List

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


def _get_default_host() -> Optional[str]:
    return os.getenv("ALPHAFROG_DEBUG_DEFAULT_HOST")


def _resolve_host(host: Optional[str]) -> str:
    resolved = host or _get_default_host()
    if not resolved:
        raise ValueError("host is required and no default host is set")
    if not _HOST_RE.match(resolved):
        raise ValueError("invalid host format")
    allowed = _env_list("ALPHAFROG_DEBUG_SSH_HOSTS")
    if allowed and resolved not in allowed:
        raise ValueError("host is not in ALPHAFROG_DEBUG_SSH_HOSTS")
    return resolved


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


async def _run_ssh(
    host: str,
    remote_args: List[str],
    timeout_seconds: Optional[int] = None,
    max_bytes: Optional[int] = None,
) -> dict:
    cmd = _ssh_base_args(host) + remote_args
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
async def remote_docker_ps(host: Optional[str] = None) -> dict:
    """List docker containers on the remote host.

    Args:
        host: SSH host alias (default: ALPHAFROG_DEBUG_DEFAULT_HOST).

    Returns:
        dict with:
        - ok/exit_code/timed_out/duration_ms/command/stdout/stderr
        - items: parsed docker ps rows (list of dicts, when available)
    """
    resolved = _resolve_host(host)
    # Quote format to avoid remote shell brace expansion via ssh command string.
    format_arg = "'{{json .}}'"
    result = await _run_ssh(resolved, _docker_cmd() + ["ps", "--format", format_arg])
    items = []
    if result["stdout"]:
        for line in result["stdout"].splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                items.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    result["items"] = items
    return result


@mcp.tool()
async def remote_git_log(
    host: Optional[str] = None,
    repo_path: Optional[str] = None,
    limit: Optional[int] = 10,
) -> dict:
    """Show recent git log on the remote host.

    Args:
        host: SSH host alias (default: ALPHAFROG_DEBUG_DEFAULT_HOST).
        repo_path: remote repo path (default: ALPHAFROG_DEBUG_DEFAULT_REPO_PATH).
        limit: max commits to return (clamped 1..200).

    Returns:
        dict with ok/exit_code/timed_out/duration_ms/command/stdout/stderr.
    """
    resolved = _resolve_host(host)
    repo = repo_path or os.getenv("ALPHAFROG_DEBUG_DEFAULT_REPO_PATH")
    if not repo:
        raise ValueError("repo_path is required and no default repo path is set")
    limit_val = _clamp_int(limit, 10, 1, 200)
    remote_args = _git_cmd() + ["-C", repo, "log", f"-n{limit_val}", "--oneline", "--decorate"]
    return await _run_ssh(resolved, remote_args)


@mcp.tool()
async def remote_docker_logs(
    host: Optional[str] = None,
    container: str = "",
    tail: Optional[int] = 200,
    since: Optional[str] = None,
    grep: Optional[str] = None,
    timestamps: bool = True,
    max_bytes: Optional[int] = 20000,
    timeout_seconds: Optional[int] = 30,
) -> dict:
    """Fetch docker logs on the remote host (non-follow).

    Args:
        host: SSH host alias (default: ALPHAFROG_DEBUG_DEFAULT_HOST).
        container: container name/id (required).
        tail: number of lines (clamped 1..10000, default 200).
        since: docker --since value (e.g. '10m' or ISO time).
        grep: substring filter, or regex via 're:<pattern>'.
        timestamps: include timestamps in output (default true).
        max_bytes: truncate stdout/stderr to this many bytes.
        timeout_seconds: overall timeout for command.

    Returns:
        dict with ok/exit_code/timed_out/duration_ms/command/stdout/stderr.
    """
    if not container:
        raise ValueError("container is required")
    resolved = _resolve_host(host)
    tail_val = _clamp_int(tail, 200, 1, 10000)
    args = _docker_cmd() + ["logs", f"--tail={tail_val}"]
    if timestamps:
        args.append("--timestamps")
    if since:
        args.append(f"--since={since}")
    args.append(container)
    result = await _run_ssh(resolved, args, timeout_seconds=timeout_seconds, max_bytes=max_bytes)
    if grep:
        result["stdout"] = _filter_output(result["stdout"], grep)
    return result


@mcp.tool()
async def remote_docker_follow(
    host: Optional[str] = None,
    container: str = "",
    follow_seconds: Optional[int] = 15,
    tail: Optional[int] = 200,
    since: Optional[str] = None,
    grep: Optional[str] = None,
    timestamps: bool = True,
    max_bytes: Optional[int] = 50000,
) -> dict:
    """Follow docker logs on the remote host for a limited time.

    Args:
        host: SSH host alias (default: ALPHAFROG_DEBUG_DEFAULT_HOST).
        container: container name/id (required).
        follow_seconds: follow duration (clamped 1..300, default 15).
        tail: number of lines before follow (clamped 1..10000, default 200).
        since: docker --since value (e.g. '10m' or ISO time).
        grep: substring filter, or regex via 're:<pattern>'.
        timestamps: include timestamps in output (default true).
        max_bytes: truncate stdout/stderr to this many bytes.

    Returns:
        dict with ok/exit_code/timed_out/duration_ms/command/stdout/stderr.
    """
    if not container:
        raise ValueError("container is required")
    resolved = _resolve_host(host)
    follow_val = _clamp_int(follow_seconds, 15, 1, 300)
    tail_val = _clamp_int(tail, 200, 1, 10000)
    args = _docker_cmd() + ["logs", "-f", f"--tail={tail_val}"]
    if timestamps:
        args.append("--timestamps")
    if since:
        args.append(f"--since={since}")
    args.append(container)
    result = await _run_ssh(resolved, args, timeout_seconds=follow_val, max_bytes=max_bytes)
    if grep:
        result["stdout"] = _filter_output(result["stdout"], grep)
    return result


if __name__ == "__main__":
    mcp.run()
