# alphafrog-debug-mcp

基于 **Node.js + TypeScript** 的 MCP 服务（stdio），用于通过 SSH 远程调试（`docker ps` / `docker logs` / `git log`）以及对 PostgreSQL 的只读查询。

调用方（Agent）在工具参数中**仅选择** `test` 或 `prod`；真实 SSH 别名、数据库连接串等在 **MCP 进程环境** 中配置，勿写入可被误提交的仓库文件。

## 环境要求

- **Node.js 20+**
- 本机已安装 `ssh`，且 `~/.ssh/config`、私钥路径对当前用户可用（推荐 `IdentityFile` 使用 `~/.ssh/xxx.pem` 等形式，避免混用宿主机绝对路径与容器内路径）。

## 安装与构建

```bash
cd alphafrogDebugMCP
npm install
npm run build
```

构建产物为 `dist/server.js`。

## 运行（stdio）

```bash
node dist/server.js
```

进程启动后仅向 **stderr** 打一行状态日志；**不要**向 stdout 打印普通日志，否则会破坏 MCP 的 JSON-RPC。

可选：在仓库根目录放置 `.env`，或通过环境变量 `ALPHAFROG_DEBUG_DOTENV_PATH` 指向自定义 dotenv 文件（与加载逻辑见源码）。

## 工具列表

所有涉及远程 SSH 的工具均使用 **`env`**：`"test"` 或 `"prod"`。

- `remote_docker_ps(env)` — 列出远程容器（name / image / status / ports）
- `remote_git_log(env, repo_path?, limit?)` — 远程 `git log`
- `remote_docker_logs(env, container, tail?, grep?, timestamps?, max_bytes?, timeout_seconds?)` — 抓取容器日志
- `remote_docker_follow(env, container, follow_seconds?, tail?, grep?, timestamps?, max_bytes?)` — 限时 follow 日志
- `remote_pg_query(env, sql)` — 只读 `SELECT`（仅 `alphafrog_*` 表，最多 100 行）

失败时返回的 `error` 为泛化说明，**不包含**服务端内部环境变量名或真实 SSH 主机名。SSH 类成功返回中**不包含**本地执行的 `command` 字段。

`grep` 支持子串；正则使用 `re:<pattern>`。

## 进程模型（是不是「一直挂着」？）

stdio MCP 的常规行为是：**由 Cursor / Codex 在需要与 MCP 通信时启动子进程**，会话结束或客户端断开连接后，**子进程退出**，不会单独在后台常驻占资源（与「自己手动 `node dist/server.js` 一直不关」不是一回事）。

若你在 Cursor 里关闭该 MCP 或退出 IDE，对应进程也会结束。

## 与 Codex / Cursor 集成

先在 `alphafrogDebugMCP` 下执行 **`npm install`**（会触发 `prepare` 跑 `npm run build`，生成 `dist/server.js`）。

### 方式 A：`node` + **绝对路径**（Cursor 推荐）

Cursor 启动 MCP 时，**`cwd` 有时不会按预期生效**。若用 `npx --package=.` 却未在包目录执行，会出现 `npm 404`（误去 registry 拉 `alphafrog-debug-mcp`）或 `ENOENT .../package.json`。因此 **在 `~/.cursor/mcp.json` 里优先用下面这种**，不依赖 `cwd`：

```json
{
  "mcpServers": {
    "alphafrog-micro-debug": {
      "command": "node",
      "args": ["/绝对路径/alphafrog-micro/alphafrogDebugMCP/dist/server.js"],
      "env": {
        "ALPHAFROG_DEBUG_SSH_CONFIG": "/你的用户名/.ssh/config",
        "ALPHAFROG_DEBUG_SSH_HOSTS": "别名1,别名2",
        "ALPHAFROG_DEBUG_SSH_HOST_TEST": "别名1",
        "ALPHAFROG_DEBUG_SSH_HOST_PROD": "别名2",
        "ALPHAFROG_DEBUG_DEFAULT_REPO_PATH": "~/alphafrog/alphafrog-micro",
        "ALPHAFROG_PG_TEST_DSN": "postgresql://...",
        "ALPHAFROG_PG_PROD_DSN": "postgresql://..."
      }
    }
  }
}
```

仓库内占位示例：[cursor-mcp.example.json](cursor-mcp.example.json)。敏感连接串只放本机，勿提交 git。

### 方式 B：`node` + 相对路径 + `cwd`

若确认你的客户端会正确传递 `cwd`，可写：

```json
"command": "node",
"args": ["dist/server.js"],
"cwd": "/绝对路径/alphafrog-micro/alphafrogDebugMCP"
```

### 方式 C：终端里用过的 `npx --package=.`（不推荐配进 Cursor）

在**本机终端**、`cwd` 为 `alphafrogDebugMCP` 时，`npx --yes --package=. alphafrog-debug-mcp` 可用；但 **不要**只写 `npx alphafrog-debug-mcp`（会去 npm 找同名包，未发布则 404）。

### Codex（`.codex/config.toml`）

使用 `command = "node"` + `args` 指向 **`dist/server.js` 的绝对路径**（或与「方式 B」等价的 `cwd` + `dist/server.js`）。

若使用 `ALPHAFROG_DEBUG_SSH_CONFIG`，请指向**本机**的 OpenSSH 配置文件（例如 `~/.ssh/config` 展开后的路径）。

## 附录：服务端环境变量（仅供人类运维）

| 用途 | 变量名 |
|------|--------|
| 测试/生产 SSH Host 别名 | `ALPHAFROG_DEBUG_SSH_HOST_TEST`、`ALPHAFROG_DEBUG_SSH_HOST_PROD` |
| 允许的 SSH 别名白名单（逗号分隔；非空则校验） | `ALPHAFROG_DEBUG_SSH_HOSTS` |
| SSH config / 额外参数 / docker、git 命令前缀 | `ALPHAFROG_DEBUG_SSH_CONFIG`、`ALPHAFROG_DEBUG_SSH_ARGS`、`ALPHAFROG_DEBUG_DOCKER_CMD`、`ALPHAFROG_DEBUG_GIT_CMD` |
| 远程仓库路径 | `ALPHAFROG_DEBUG_REPO_PATH_TEST`、`ALPHAFROG_DEBUG_REPO_PATH_PROD`、`ALPHAFROG_DEBUG_DEFAULT_REPO_PATH` |
| PostgreSQL DSN | `ALPHAFROG_PG_TEST_DSN`、`ALPHAFROG_PG_PROD_DSN` |

## 历史说明

早期曾提供 Python + Docker 镜像方案；已改为 **仅维护 Node 实现**，以避免容器内 SSH 与宿主机路径不一致等问题。
