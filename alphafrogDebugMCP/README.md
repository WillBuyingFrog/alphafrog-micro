# alphafrog-debug-mcp

Minimal MCP server for remote debugging over SSH (docker ps/logs, git log).

## Setup

1) Install dependencies:

```bash
pip install -r requirements.txt
```

2) Add a `.env` at repo root (or point to a custom path with `ALPHAFROG_DEBUG_DOTENV_PATH`).

Example:

```bash
ALPHAFROG_DEBUG_SSH_HOSTS=frog-aliyun-sg-proxy,prod-bastion
ALPHAFROG_DEBUG_DEFAULT_HOST=frog-aliyun-sg-proxy
ALPHAFROG_DEBUG_DEFAULT_REPO_PATH=/srv/alphafrog/alphafrog-micro
ALPHAFROG_DEBUG_SSH_CONFIG=/home/app/.ssh/config
ALPHAFROG_DEBUG_SSH_ARGS=-o StrictHostKeyChecking=no
ALPHAFROG_DEBUG_DOCKER_CMD=docker
```

3) Run the MCP server (stdio):

```bash
python server.py
```

## Tools

- `remote_docker_ps(host)`
- `remote_git_log(host, repo_path, limit)`
- `remote_docker_logs(host, container, tail, since, grep, timestamps, max_bytes, timeout_seconds)`
- `remote_docker_follow(host, container, follow_seconds, tail, since, grep, timestamps, max_bytes)`

Notes:
- `grep` supports substring match. For regex use `re:<pattern>`.
- `since` is passed to `docker logs --since` as-is (e.g. `10m`, `2026-01-26T10:00:00`).

## Credentials 安全隔离说明

敏感配置（如真实 SSH host/IP、数据库 DSN、API key）不建议放在项目目录内的 `.env` 或 `.mcp.json` 中，
因为项目目录内文件可能被误提交到 git，也可能被 AI agent 直接读取。

推荐做法：将敏感值放在用户本机全局配置 `~/.claude.json` 的 `env` block 中，仅将非敏感配置保留在项目侧。

示例：

```jsonc
{
  "mcpServers": {
    "alphafrog-micro-debug": {
      "command": "docker",
      "args": ["run", "-i", "--rm", "..."],
      "env": {
        "ALPHAFROG_DEBUG_SSH_HOSTS": "frog-aliyun-sg-proxy",
        "ALPHAFROG_DEBUG_DEFAULT_HOST": "frog-aliyun-sg-proxy",
        "ALPHAFROG_PG_PROD_DSN": "postgresql://<USERNAME>:<PASSWORD>@192.0.2.10:5432/alphafrog",
        "ALPHAFROG_PG_TEST_DSN": "postgresql://<USERNAME>:<PASSWORD>@192.0.2.11:5432/alphafrog_test"
      }
    }
  }
}
```

建议放在 `~/.claude.json` 的敏感项包括：

- `ALPHAFROG_PG_TEST_DSN`
- `ALPHAFROG_PG_PROD_DSN`
- 真实 SSH 配置路径、私网 host/IP、密码类配置

项目侧 `.env.example` / `.mcp.json` 只保留非敏感默认值、SSH alias 名、功能开关等信息。

## Docker (optional)

Build:

```bash
docker build -t frog:alphafrog-debug-mcp .
```

Run with a mounted SSH config and keys (read-only). For local usage, directly mount `~/.ssh`:

```bash
docker run --rm -i \
  -v $HOME/.ssh:/home/app/.ssh:ro \
  -e ALPHAFROG_DEBUG_SSH_CONFIG=/home/app/.ssh/config \
  -e ALPHAFROG_DEBUG_SSH_HOSTS=frog-aliyun-sg-proxy \
  -e ALPHAFROG_DEBUG_DEFAULT_HOST=frog-aliyun-sg-proxy \
  alphafrog-debug-mcp
```

If you want agent forwarding instead of mounting keys, start the container with `-e SSH_AUTH_SOCK` and mount the agent socket.
