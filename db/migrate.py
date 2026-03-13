#!/usr/bin/env python3
"""
AlphaFrog 数据库迁移工具

功能：
1. 自动检测并执行未运行的迁移脚本
2. 记录迁移历史到 schema_migrations 表
3. 支持交互式确认和批量执行
4. 支持查看迁移状态

使用方法：
    # 查看当前迁移状态
    python db/migrate.py status
    
    # 执行所有待执行的迁移（交互式确认）
    python db/migrate.py migrate
    
    # 强制执行，不提示确认
    python db/migrate.py migrate --force
    
    # 指定配置文件
    python db/migrate.py migrate --config /path/to/config.yml

配置文件格式（YAML）：
    database:
      host: localhost
      port: 5432
      name: alphafrog
      user: postgres
      password: your_password
"""

import argparse
import hashlib
import os
import re
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import List, Optional, Tuple

import psycopg2
from psycopg2 import sql

# 默认配置
DEFAULT_MIGRATIONS_DIR = Path(__file__).parent / "migrations"
DEFAULT_CONFIG_PATHS = [
    Path(__file__).parent / "migrate_config.yml",
    Path.cwd() / "db" / "migrate_config.yml",
]

# 颜色输出
class Colors:
    GREEN = '\033[92m'
    RED = '\033[91m'
    YELLOW = '\033[93m'
    BLUE = '\033[94m'
    CYAN = '\033[96m'
    RESET = '\033[0m'


def log_success(msg: str):
    print(f"{Colors.GREEN}✓ {msg}{Colors.RESET}")


def log_error(msg: str):
    print(f"{Colors.RED}✗ {msg}{Colors.RESET}")


def log_info(msg: str):
    print(f"{Colors.BLUE}ℹ {msg}{Colors.RESET}")


def log_warning(msg: str):
    print(f"{Colors.YELLOW}⚠ {msg}{Colors.RESET}")


def log_step(msg: str):
    print(f"{Colors.CYAN}→ {msg}{Colors.RESET}")


class DatabaseConfig:
    """数据库配置"""
    def __init__(self, host: str, port: int, name: str, user: str, password: str):
        self.host = host
        self.port = port
        self.name = name
        self.user = user
        self.password = password

    @classmethod
    def from_yaml(cls, path: Path) -> "DatabaseConfig":
        """从YAML文件加载配置"""
        try:
            import yaml
        except ImportError:
            raise ImportError("需要安装 PyYAML: pip install pyyaml")

        with open(path, "r", encoding="utf-8") as f:
            config = yaml.safe_load(f)

        db_config = config.get("database", {})
        return cls(
            host=db_config.get("host", "localhost"),
            port=db_config.get("port", 5432),
            name=db_config.get("name", "alphafrog"),
            user=db_config.get("user", "postgres"),
            password=db_config.get("password", ""),
        )

    def to_dsn(self) -> str:
        """转换为DSN连接字符串"""
        return f"host={self.host} port={self.port} dbname={self.name} user={self.user} password={self.password}"


class Migration:
    """单个迁移记录"""
    def __init__(self, version: str, description: str, filename: str, filepath: Path):
        self.version = version
        self.description = description
        self.filename = filename
        self.filepath = filepath
        self.checksum = self._calculate_checksum()

    def _calculate_checksum(self) -> str:
        """计算文件MD5校验和"""
        with open(self.filepath, "rb") as f:
            return hashlib.md5(f.read()).hexdigest()

    @classmethod
    def from_file(cls, filepath: Path) -> Optional["Migration"]:
        """从文件路径解析迁移信息"""
        filename = filepath.name
        # 匹配格式: 001_description_here.sql 或 001_description.sql
        match = re.match(r"^(\d+)_(.+)\.sql$", filename)
        if not match:
            return None

        version = match.group(1)
        description = match.group(2).replace("_", " ")
        return cls(version, description, filename, filepath)


class MigrationManager:
    """迁移管理器"""

    def __init__(self, db_config: DatabaseConfig, migrations_dir: Path):
        self.db_config = db_config
        self.migrations_dir = migrations_dir
        self.conn = None

    def connect(self) -> bool:
        """连接数据库"""
        try:
            self.conn = psycopg2.connect(self.db_config.to_dsn())
            self.conn.autocommit = False
            return True
        except Exception as e:
            log_error(f"数据库连接失败: {e}")
            return False

    def close(self):
        """关闭数据库连接"""
        if self.conn:
            self.conn.close()
            self.conn = None

    def ensure_migration_table(self):
        """确保迁移跟踪表存在"""
        # 读取创建表的SQL
        init_sql_path = self.migrations_dir / "001_create_migration_tracking_table.sql"
        if not init_sql_path.exists():
            # 如果文件不存在，使用内置SQL
            create_sql = """
            CREATE TABLE IF NOT EXISTS schema_migrations (
                id SERIAL PRIMARY KEY,
                version VARCHAR(64) NOT NULL UNIQUE,
                description TEXT,
                filename VARCHAR(256) NOT NULL,
                checksum VARCHAR(64),
                executed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                execution_time_ms INTEGER,
                executed_by VARCHAR(128),
                success BOOLEAN DEFAULT TRUE
            );
            CREATE INDEX IF NOT EXISTS idx_schema_migrations_version ON schema_migrations(version);
            """
        else:
            with open(init_sql_path, "r", encoding="utf-8") as f:
                create_sql = f.read()

        with self.conn.cursor() as cur:
            cur.execute(create_sql)
            self.conn.commit()

    def get_executed_migrations(self) -> dict:
        """获取已执行的迁移"""
        with self.conn.cursor() as cur:
            cur.execute("""
                SELECT version, checksum, executed_at, success
                FROM schema_migrations
                ORDER BY version
            """)
            rows = cur.fetchall()
            return {row[0]: {"checksum": row[1], "executed_at": row[2], "success": row[3]} for row in rows}

    def get_available_migrations(self) -> List[Migration]:
        """获取可用的迁移文件列表"""
        migrations = []
        if not self.migrations_dir.exists():
            log_error(f"迁移目录不存在: {self.migrations_dir}")
            return migrations

        for filepath in sorted(self.migrations_dir.glob("*.sql")):
            migration = Migration.from_file(filepath)
            if migration:
                migrations.append(migration)

        return migrations

    def get_pending_migrations(self) -> List[Migration]:
        """获取待执行的迁移"""
        executed = self.get_executed_migrations()
        available = self.get_available_migrations()

        pending = []
        for migration in available:
            if migration.version not in executed:
                pending.append(migration)

        return pending

    def execute_migration(self, migration: Migration) -> bool:
        """执行单个迁移"""
        log_step(f"执行迁移 {migration.version}: {migration.description}")

        # 读取SQL内容
        with open(migration.filepath, "r", encoding="utf-8") as f:
            sql_content = f.read()

        # 执行迁移
        start_time = time.time()
        executed_by = os.environ.get("USER") or os.environ.get("USERNAME") or "unknown"

        try:
            with self.conn.cursor() as cur:
                cur.execute(sql_content)
                
                # 记录迁移
                cur.execute("""
                    INSERT INTO schema_migrations 
                    (version, description, filename, checksum, execution_time_ms, executed_by, success)
                    VALUES (%s, %s, %s, %s, %s, %s, %s)
                    ON CONFLICT (version) DO NOTHING
                """, (
                    migration.version,
                    migration.description,
                    migration.filename,
                    migration.checksum,
                    int((time.time() - start_time) * 1000),
                    executed_by,
                    True
                ))
                
                self.conn.commit()
                log_success(f"迁移 {migration.version} 执行成功")
                return True

        except Exception as e:
            self.conn.rollback()
            log_error(f"迁移 {migration.version} 执行失败: {e}")
            return False

    def status(self):
        """显示迁移状态"""
        print("\n" + "=" * 70)
        print("数据库迁移状态")
        print("=" * 70)
        print(f"数据库: {self.db_config.host}:{self.db_config.port}/{self.db_config.name}")
        print(f"迁移目录: {self.migrations_dir}")
        print("=" * 70)

        executed = self.get_executed_migrations()
        available = self.get_available_migrations()

        if not available:
            print("\n未找到迁移文件")
            return

        print(f"\n{'版本':<12} {'状态':<12} {'执行时间':<20} {'描述'}")
        print("-" * 70)

        for migration in available:
            if migration.version in executed:
                info = executed[migration.version]
                status = "✓ 已执行" if info["success"] else "✗ 失败"
                executed_at = info["executed_at"].strftime("%Y-%m-%d %H:%M:%S") if info["executed_at"] else "-"
                print(f"{migration.version:<12} {status:<12} {executed_at:<20} {migration.description}")
            else:
                print(f"{migration.version:<12} {'○ 待执行':<12} {'-':<20} {migration.description}")

        pending_count = len([m for m in available if m.version not in executed])
        print("-" * 70)
        print(f"总计: {len(available)} 个迁移 | 已执行: {len(executed)} 个 | 待执行: {pending_count} 个")
        print("=" * 70 + "\n")

    def migrate(self, force: bool = False, target_version: Optional[str] = None):
        """执行迁移"""
        pending = self.get_pending_migrations()

        if not pending:
            log_success("所有迁移已是最新状态，无需执行")
            return True

        # 如果指定了目标版本，只执行到该版本
        if target_version:
            pending = [m for m in pending if int(m.version) <= int(target_version)]
            if not pending:
                log_warning(f"目标版本 {target_version} 之前的所有迁移已执行")
                return True

        print("\n" + "=" * 70)
        print("准备执行以下迁移")
        print("=" * 70)
        for migration in pending:
            print(f"  {migration.version}: {migration.description}")
        print("=" * 70 + "\n")

        if not force:
            response = input("确认执行以上迁移? [y/N]: ").strip().lower()
            if response != "y":
                log_info("已取消迁移")
                return False

        print()
        success_count = 0
        fail_count = 0

        for migration in pending:
            if self.execute_migration(migration):
                success_count += 1
            else:
                fail_count += 1
                log_error(f"迁移 {migration.version} 失败，停止后续迁移")
                break

        print("\n" + "=" * 70)
        print("迁移结果")
        print("=" * 70)
        log_success(f"成功: {success_count} 个")
        if fail_count > 0:
            log_error(f"失败: {fail_count} 个")
        print("=" * 70 + "\n")

        return fail_count == 0


def find_config_file(config_path: Optional[str] = None) -> Optional[Path]:
    """查找配置文件"""
    if config_path:
        path = Path(config_path)
        if path.exists():
            return path
        log_error(f"指定的配置文件不存在: {config_path}")
        return None

    for path in DEFAULT_CONFIG_PATHS:
        if path.exists():
            return path

    return None


def main():
    parser = argparse.ArgumentParser(
        description="AlphaFrog 数据库迁移工具",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  # 查看迁移状态
  python db/migrate.py status
  
  # 执行所有待执行的迁移（交互式确认）
  python db/migrate.py migrate
  
  # 强制执行，不提示确认
  python db/migrate.py migrate --force
  
  # 指定配置文件
  python db/migrate.py migrate --config db/migrate_config.yml
        """
    )
    parser.add_argument(
        "command",
        choices=["status", "migrate"],
        help="命令: status=查看状态, migrate=执行迁移"
    )
    parser.add_argument("--config", "-c", help="配置文件路径")
    parser.add_argument("--force", "-f", action="store_true", help="强制执行，不提示确认")
    parser.add_argument("--target", "-t", help="目标版本号，只执行到该版本")
    parser.add_argument("--migrations-dir", "-d", help="迁移脚本目录路径")

    args = parser.parse_args()

    # 查找配置文件
    config_path = find_config_file(args.config)
    if not config_path:
        log_error("未找到配置文件，请创建 db/migrate_config.yml 或使用 --config 指定")
        print("\n配置文件示例:")
        print("""
database:
  host: localhost
  port: 5432
  name: alphafrog
  user: postgres
  password: your_password
        """)
        sys.exit(1)

    # 加载配置
    try:
        db_config = DatabaseConfig.from_yaml(config_path)
    except Exception as e:
        log_error(f"加载配置文件失败: {e}")
        sys.exit(1)

    # 确定迁移目录
    migrations_dir = Path(args.migrations_dir) if args.migrations_dir else DEFAULT_MIGRATIONS_DIR

    # 创建迁移管理器
    manager = MigrationManager(db_config, migrations_dir)

    # 连接数据库
    if not manager.connect():
        sys.exit(1)

    try:
        # 确保迁移表存在
        manager.ensure_migration_table()

        # 执行命令
        if args.command == "status":
            manager.status()
        elif args.command == "migrate":
            success = manager.migrate(force=args.force, target_version=args.target)
            sys.exit(0 if success else 1)
    finally:
        manager.close()


if __name__ == "__main__":
    main()
