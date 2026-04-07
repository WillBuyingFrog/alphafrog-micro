# AlphaFrog 迁移工具设计与使用说明

## 概述

AlphaFrog 迁移工具是一个基于 Python 的数据库和配置迁移框架，支持从 v0.2 开始任意旧版本到任意更新版本的自动化迁移。

## 设计思路

### 版本-模块二维矩阵

迁移脚本按版本和业务模块组织：

- `init/`：初始化 DDL，用于从零创建数据库
- `upgrades/<版本号>/`：各版本的增量迁移脚本

每个版本可以包含两类脚本：
- `.sql`：数据库 DDL/DML 变更
- `.py`：配置检查脚本（环境变量、配置文件结构检查）

### 版本清单（version_manifest.json）

`version_manifest.json` 是迁移工具的核心配置文件，定义了已发布版本的：

注意：开发中的版本不写入 manifest，使用 `--to current` 模式直接扫描文件系统。
- `tag`：版本标签（如 v0.4）
- `commit`：对应的 git commit hash
- `services`：该版本包含的微服务列表
- `infra`：该版本依赖的基础设施列表
- `upgrades`：该版本需要执行的升级脚本目录

迁移工具根据 `from_version` 和 `to_version`，从清单中计算出需要执行的脚本序列。

## 目录结构

```
db/
├── migrate.py                    # 迁移工具主脚本
├── migrate_config.example.yml    # 配置示例
├── version_manifest.json         # 版本清单
├── migrations/
│   ├── init/                     # 初始化 DDL
│   │   ├── 001_portfolio.sql
│   │   ├── 002_user.sql
│   │   ├── 003_domestic_data.sql
│   │   └── 004_agent.sql
│   └── upgrades/                 # 增量迁移
│       ├── v0.3-phase1/
│       │   ├── 001_agent_base.sql
│       │   └── 002_config_check.py
│       ├── v0.4/
│       │   ├── 001_agent_expired.sql
│       │   ├── 002_auth_invite.sql
│       │   ├── 003_admin_credit_gov.sql
│       │   ├── 004_agent_credit.sql
│       │   ├── 005_agent_perf_index.sql
│       │   ├── 006_multi_turn_message.sql
│       │   └── 007_config_check.py
│       ├── v0.5/
│       │   ├── 001_tushare_interfaces.sql
│       │   └── 002_env_and_config_check.py
│       └── v0.6/
│           ├── 001_rag_documents.sql
│           └── 002_config_check.py
└── MIGRATION_DESIGN.md           # 本文档
```

## 使用方法

### 1. 安装依赖

```bash
pip install psycopg2-binary pyyaml
```

### 2. 创建配置文件

```bash
cp db/migrate_config.example.yml db/migrate_config.yml
# 编辑 db/migrate_config.yml，填写数据库连接信息
```

### 3. 查看迁移状态

```bash
python db/migrate.py status
```

### 4. 查看迁移计划

```bash
# 自动检测当前版本
python db/migrate.py plan --from v0.3-phase1 --to v0.5

# 从当前版本到最新版本
python migrate/migrate.py plan --auto --to current
```

### 5. 执行迁移

```bash
# 自动检测当前版本并迁移到最新发布版本
python migrate/migrate.py migrate --auto

# 指定版本范围
python migrate/migrate.py migrate --from v0.2 --to v0.5

# 迁移到当前分支最新状态（开发分支验证）
python migrate/migrate.py migrate --from v0.5 --to current

# 强制执行，跳过确认
python migrate/migrate.py migrate --auto --force
```

## 添加新版本的迁移脚本

当发布新版本（如 v0.7）时，按以下步骤添加迁移支持：

### 1. 创建升级目录

```bash
mkdir db/migrations/upgrades/v0.7
```

### 2. 编写迁移脚本

按序号命名脚本：

```
v0.7/
├── 001_new_feature.sql      # SQL 迁移
├── 002_another_change.sql
└── 003_config_check.py      # 配置检查
```

SQL 脚本规范：
- 使用 `IF NOT EXISTS` 和 `IF EXISTS` 保证幂等性
- 每个脚本只包含一个逻辑变更
- 添加注释说明变更内容

Python 脚本规范：
- 输出格式使用 `[OK]`、`[WARN]`、`[INFO]` 前缀
- 返回 0 表示检查通过（或仅提示）
- 返回非 0 表示需要用户手动处理（但迁移工具不会阻塞）

### 3. 更新版本清单

在 `version_manifest.json` 中添加新版本：

```json
{
  "tag": "v0.7",
  "commit": "abc1234",
  "services": ["...", "new-service"],
  "infra": ["redis", "rabbitmq", "meilisearch"],
  "upgrades": ["v0.7/"]
}
```

### 4. 更新文档

在 `db/MIGRATION_DESIGN.md` 中记录新版本的重要变更提醒。

## 添加新的业务模块

当引入新的业务模块时，可能需要：

1. 在 `init/` 中添加模块的基线 DDL 脚本
2. 在对应版本的 `upgrades/` 中添加增量迁移脚本
3. 在 `version_manifest.json` 中更新 `services` 列表

## schema_migrations 表结构

迁移工具使用 `schema_migrations` 表记录迁移历史：

| 字段 | 说明 |
|------|------|
| version | 脚本版本号（如 001） |
| description | 脚本描述 |
| filename | 文件名 |
| checksum | 文件 MD5 校验和 |
| executed_at | 执行时间 |
| execution_time_ms | 执行耗时（毫秒） |
| executed_by | 执行用户 |
| success | 是否成功 |
| target_version | 本次迁移的目标版本（如 v0.5） |
| module | 所属模块/版本 |
| migration_type | sql 或 python |

## 注意事项

1. 迁移前务必备份数据库和配置文件
2. Python 检查脚本的失败不会阻塞后续 SQL 迁移的执行
3. 迁移工具不会自动修改 `.env` 或配置文件的内容，只负责检查并提示
4. 对于基础设施变更（如 Kafka -> RabbitMQ），需要用户手动完成
