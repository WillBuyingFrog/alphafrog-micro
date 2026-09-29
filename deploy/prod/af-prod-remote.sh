#!/usr/bin/env bash
# 从开发机调用生产机本机接待命令 af-prod 的 SSH 包装。
#
# 生产机 authorized_keys 把专用钥匙绑在 command="/opt/alphafrog-prod/bin/af-prod-shell"
# 与 restrict 上：服务端只跑 af-prod 白名单子命令，拿不到 shell，不能转发端口。
# 写操作必须带有效写 token；只读操作用团队共享的只读 token。
#
# 环境变量：
#   AF_PROD_SSH    生产机 SSH 目标（例如 user@prod-host），每台开发机自己配置。
#   AF_PROD_KEY    专用私钥路径，默认 ~/.ssh/af-prod-ed25519。
#   AF_PROD_TOKEN  access token。经 SendEnv 传到生产机，不出现在命令行。
#
# 用法：bash deploy/prod/af-prod-remote.sh <af-prod 子命令及参数>
set -euo pipefail

if [ -z "${AF_PROD_SSH:-}" ]; then
  echo "AF_PROD_SSH 未设置。请在本机环境导出生产机 SSH 目标（例如 user@prod-host）后重试。" >&2
  exit 2
fi

if [ -z "${AF_PROD_TOKEN:-}" ]; then
  echo "AF_PROD_TOKEN 未设置。只读用共享只读 token；改生产用单独签发的写 token。" >&2
  exit 2
fi

KEY="${AF_PROD_KEY:-$HOME/.ssh/af-prod-ed25519}"
if [ ! -f "$KEY" ]; then
  echo "专用私钥不存在: $KEY。生成方式: ssh-keygen -t ed25519 -f \"$KEY\" -C prod-agent，公钥交给生产侧管理员登记。" >&2
  exit 2
fi

exec ssh \
  -i "$KEY" \
  -o IdentitiesOnly=yes \
  -o BatchMode=yes \
  -o SendEnv=AF_PROD_TOKEN \
  "$AF_PROD_SSH" af-prod "$@"
