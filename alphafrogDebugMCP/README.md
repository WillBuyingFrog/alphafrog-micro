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
