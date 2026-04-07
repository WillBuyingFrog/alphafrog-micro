# AlphaFrog 数据库迁移工具

## 简介

AlphaFrog 迁移工具支持从 v0.2 开始任意旧版本到任意更新版本的数据库和配置迁移。

## 快速开始

### 1. 安装依赖

```bash
pip install psycopg2-binary pyyaml
```

### 2. 配置文件

迁移工具会自动查找以下配置文件（按优先级）：

1. `migrate/migrate_config.yml`（YAML 格式）
2. `.env`（项目根目录，读取 `AF_DB_MAIN_*` 变量）

如果项目根目录已有 `.env` 文件且包含数据库配置，迁移工具会自动使用它：

```bash
# .env 中需要包含以下变量
AF_DB_MAIN_HOST=localhost
AF_DB_MAIN_PORT=5432
AF_DB_MAIN_DATABASE=alphafrog
AF_DB_MAIN_USER=alphafrog
AF_DB_MAIN_PASSWORD=your_password
```

或者手动创建 YAML 配置：

```bash
cp migrate/migrate_config.example.yml migrate/migrate_config.yml
# 编辑 migrate/migrate_config.yml
```

### 3. 查看迁移状态

```bash
python migrate/migrate.py status
```

### 4. 执行迁移

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

## 目录结构

```
db/
├── migrate.py              # 迁移工具主脚本
├── migrate_config.yml      # 数据库连接配置（需手动创建）
├── migrate_config.example.yml  # 配置示例
├── version_manifest.json   # 版本清单
├── MIGRATION_DESIGN.md     # 设计文档
└── migrations/             # 迁移脚本目录
    ├── init/               # 初始化 DDL
    └── upgrades/           # 增量迁移
        ├── v0.3-phase1/
        ├── v0.4/
        ├── v0.5/
        └── v0.6/
```

## 添加新的迁移脚本

1. 在 `migrate/migrations/upgrades/<版本号>/` 目录下创建新的脚本
2. 文件名格式：`{3位序号}_{描述}.sql` 或 `{3位序号}_{描述}.py`
3. 更新 `version_manifest.json`

详细说明请参考 `MIGRATION_DESIGN.md`。

## 迁移脚本规范

- SQL 脚本使用 `IF NOT EXISTS`/`IF EXISTS` 保证幂等性
- 每个脚本只包含一个逻辑变更
- Python 检查脚本使用 `[OK]`/`[WARN]`/`[INFO]` 前缀输出
- Python 脚本返回 0 表示通过，非 0 表示需要手动处理
