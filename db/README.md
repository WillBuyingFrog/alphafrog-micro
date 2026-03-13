# AlphaFrog 数据库迁移工具

## 目录结构

```
db/
├── migrate.py              # 迁移工具主脚本
├── migrate_config.yml      # 数据库连接配置（需要手动创建，参考 migrate_config.example.yml）
├── migrate_config.example.yml  # 配置示例
├── migrations/             # 迁移脚本目录
│   ├── 001_create_migration_tracking_table.sql
│   ├── 002_xxx.sql
│   └── ...
└── archived/               # 归档的迁移脚本（可选）
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

### 4. 执行迁移

```bash
# 交互式执行（推荐）
python db/migrate.py migrate

# 强制执行，不提示确认
python db/migrate.py migrate --force

# 执行到指定版本
python db/migrate.py migrate --target 010
```

## 添加新的迁移脚本

1. 在 `db/migrations/` 目录下创建新的 SQL 文件
2. 文件名格式：`{版本号}_{描述}.sql`
   - 版本号：3位数字，如 001, 002, 003...
   - 描述：使用下划线分隔的英文描述
3. 示例：`002_add_user_table.sql`

## 迁移脚本规范

- 使用 `IF NOT EXISTS` 或 `IF EXISTS` 确保脚本可重复执行（幂等性）
- 每个脚本应该只包含一个逻辑变更
- 脚本应该包含回滚逻辑（如果需要）
- 复杂变更建议拆分为多个脚本
