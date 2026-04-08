# alphafrog-debug-mcp

Minimal MCP server for remote debugging over SSH (docker ps/logs, git log) and read-only PostgreSQL queries.

调用方（Agent）在工具参数中**仅选择** `test` 或 `prod`；真实 SSH 别名、数据库连接串等在 **MCP 服务端进程环境** 中配置，勿写入可被误提交的仓库文件。

## Setup

1) Install dependencies:

```bash
pip install -r requirements.txt
```

2) 在运行 MCP 的进程环境中配置「环境与远程主机、数据库」的映射（见下文「运维附录」）。可选：在仓库根目录放置 `.env`，或通过 `ALPHAFROG_DEBUG_DOTENV_PATH` 指向自定义路径。

3) Run the MCP server (stdio):

```bash
python server.py
```

## Tools

所有涉及远程 SSH 的工具均使用 **`env`**：`"test"` 或 `"prod"`。

- `remote_docker_ps(env)` — 列出远程容器（紧凑格式：name/image/status/ports）
- `remote_git_log(env, repo_path, limit)` — 查看远程 git 日志（`repo_path` 可选，覆盖该环境在服务端配置的路径）
- `remote_docker_logs(env, container, tail, grep, timestamps, max_bytes, timeout_seconds)` — 抓取容器日志
- `remote_docker_follow(env, container, follow_seconds, tail, grep, timestamps, max_bytes)` — 限时 follow 容器日志
- `remote_pg_query(env, sql)` — 在 PostgreSQL 中执行只读 `SELECT`
  - `sql`：仅允许 `SELECT`；仅允许 `alphafrog_*` 表；最多返回 100 行

失败时返回的 `error` 为泛化说明，**不包含**服务端内部环境变量名或真实 SSH 主机名。成功时 SSH 类工具返回体中**不包含**本地执行的 `command` 字段。

Notes:

- `grep` 支持子串匹配；正则使用 `re:<pattern>`。
- `remote_docker_logs` / `remote_docker_follow` 的说明中未实现 `since` 参数；若需按时间过滤可在后续版本扩展。

## Credentials 安全隔离说明

敏感值（SSH 别名、数据库 DSN、私网地址等）建议放在用户本机全局 MCP 配置（如 `~/.claude.json` 或 Cursor MCP 的 `env`）中，不要放进仓库内 `.env`（除非该文件已确认不会被 Agent 读取且不会提交）。

面向 Agent 的文档（本节与工具说明）**刻意不写全量内部变量名**；运维人员在部署 MCP 时请参阅下方附录。

---

## 附录：服务端环境变量（仅供人类运维）

以下名称仅在配置 MCP 进程时使用，**不应**出现在工具返回的错误信息中（实现已避免）。

| 用途 | 变量名（示例职责） |
|------|-------------------|
| 测试环境 SSH Host 别名 | `ALPHAFROG_DEBUG_SSH_HOST_TEST` |
| 生产环境 SSH Host 别名 | `ALPHAFROG_DEBUG_SSH_HOST_PROD` |
| 允许的 SSH 别名白名单（逗号分隔，非空则校验） | `ALPHAFROG_DEBUG_SSH_HOSTS` |
| SSH config、额外参数、docker/git 命令前缀 | `ALPHAFROG_DEBUG_SSH_CONFIG`、`ALPHAFROG_DEBUG_SSH_ARGS`、`ALPHAFROG_DEBUG_DOCKER_CMD`、`ALPHAFROG_DEBUG_GIT_CMD` |
| 远程仓库路径（分环境；可与 `ALPHAFROG_DEBUG_DEFAULT_REPO_PATH` 搭配回退） | `ALPHAFROG_DEBUG_REPO_PATH_TEST`、`ALPHAFROG_DEBUG_REPO_PATH_PROD`、`ALPHAFROG_DEBUG_DEFAULT_REPO_PATH` |
| PostgreSQL DSN | `ALPHAFROG_PG_TEST_DSN`、`ALPHAFROG_PG_PROD_DSN` |

`~/.claude.json` 中 `mcpServers` 的 `env` 示例（占位符需替换为真实值）：

```jsonc
{
  "mcpServers": {
    "alphafrog-micro-debug": {
      "command": "python",
      "args": ["/path/to/alphafrog-micro/alphafrogDebugMCP/server.py"],
      "env": {
        "ALPHAFROG_DEBUG_SSH_HOSTS": "<hostA>,<hostB>",
        "ALPHAFROG_DEBUG_SSH_HOST_TEST": "<test-bastion-alias>",
        "ALPHAFROG_DEBUG_SSH_HOST_PROD": "<prod-bastion-alias>",
        "ALPHAFROG_DEBUG_DEFAULT_REPO_PATH": "/srv/alphafrog/alphafrog-micro",
        "ALPHAFROG_PG_TEST_DSN": "postgresql://<USER>:<PASS>@<HOST>:5432/<DB>",
        "ALPHAFROG_PG_PROD_DSN": "postgresql://<USER>:<PASS>@<HOST>:5432/<DB>"
      }
    }
  }
}
```

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
  -e ALPHAFROG_DEBUG_SSH_HOSTS=<comma-separated-aliases> \
  -e ALPHAFROG_DEBUG_SSH_HOST_TEST=<test-alias> \
  -e ALPHAFROG_DEBUG_SSH_HOST_PROD=<prod-alias> \
  frog:alphafrog-debug-mcp
```

If you want agent forwarding instead of mounting keys, start the container with `-e SSH_AUTH_SOCK` and mount the agent socket.
