#!/usr/bin/env python3
"""
AlphaFrog 数据库迁移工具（v2）

功能：
1. 自动检测当前版本（git tag + 数据库记录）
2. 根据版本计划执行迁移（支持任意旧版本到任意新版本）
3. 支持 SQL 和 Python 两种类型的迁移脚本
4. 记录迁移历史到 schema_migrations 表

使用方法：
    # 查看当前迁移状态
    python migrate/migrate.py status

    # 查看迁移计划（不执行）
    python migrate/migrate.py plan --from v0.3-phase1 --to v0.5

    # 执行所有待执行的迁移（交互式确认）
    python migrate/migrate.py migrate --auto

    # 指定版本范围迁移
    python migrate/migrate.py migrate --from v0.2 --to v0.6

    # 迁移到当前分支的最新状态（用于开发分支验证）
    python migrate/migrate.py migrate --from v0.5 --to current

    # 强制执行，不提示确认
    python migrate/migrate.py migrate --auto --force

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
import json
import os
import re
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import psycopg2
from psycopg2 import sql

# 默认配置
DEFAULT_MIGRATIONS_DIR = Path(__file__).parent / "migrations"
DEFAULT_CONFIG_PATHS = [
    Path(__file__).parent / "migrate_config.yml",
    Path.cwd() / "migrate" / "migrate_config.yml",
]
DEFAULT_MANIFEST_PATH = Path(__file__).parent / "version_manifest.json"

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
    def __init__(self, version: str, description: str, filename: str, filepath: Path, module: str = "", migration_type: str = "sql"):
        self.version = version
        self.description = description
        self.filename = filename
        self.filepath = filepath
        self.module = module
        self.migration_type = migration_type
        self.checksum = self._calculate_checksum()

    def _calculate_checksum(self) -> str:
        """计算文件MD5校验和"""
        with open(self.filepath, "rb") as f:
            return hashlib.md5(f.read()).hexdigest()

    @classmethod
    def from_file(cls, filepath: Path) -> Optional["Migration"]:
        """从文件路径解析迁移信息"""
        filename = filepath.name
        # 匹配格式: 001_description_here.sql 或 001_description.py
        match = re.match(r"^(\d+)_(.+)\.(sql|py)$", filename)
        if not match:
            return None

        version = match.group(1)
        description = match.group(2).replace("_", " ")
        migration_type = match.group(3)
        # 从父目录名推断模块
        module = filepath.parent.name if filepath.parent.name not in ["migrations", "upgrades", "init"] else ""
        return cls(version, description, filename, filepath, module, migration_type)


class VersionDetector:
    """版本检测器"""

    def __init__(self, manifest_path: Path):
        self.manifest_path = manifest_path
        self.versions = self._load_versions()

    def _load_versions(self) -> List[Dict]:
        """加载版本清单"""
        if not self.manifest_path.exists():
            return []
        with open(self.manifest_path, "r", encoding="utf-8") as f:
            data = json.load(f)
        return data.get("versions", [])

    def detect_from_git(self) -> Optional[str]:
        """通过 git 检测当前版本"""
        try:
            # 先尝试精确匹配 tag
            result = subprocess.run(
                ["git", "describe", "--tags", "--exact-match"],
                capture_output=True, text=True, cwd=Path(__file__).parent.parent
            )
            if result.returncode == 0:
                tag = result.stdout.strip()
                # 检查 tag 是否在版本清单中
                for v in self.versions:
                    if v["tag"] == tag:
                        return tag

            # 尝试从 git log 中匹配版本 tag
            result = subprocess.run(
                ["git", "log", "--oneline", "--all"],
                capture_output=True, text=True, cwd=Path(__file__).parent.parent
            )
            if result.returncode == 0:
                for line in result.stdout.split("\n"):
                    for v in self.versions:
                        if v["commit"] in line or v["tag"] in line:
                            return v["tag"]
        except Exception:
            pass
        return None

    def detect_from_db(self, conn) -> Optional[str]:
        """通过数据库记录检测当前版本"""
        try:
            with conn.cursor() as cur:
                cur.execute("""
                    SELECT target_version FROM schema_migrations
                    WHERE success = TRUE AND target_version IS NOT NULL
                    ORDER BY version DESC LIMIT 1
                """)
                row = cur.fetchone()
                if row and row[0]:
                    return row[0]
        except Exception:
            pass
        return None

    def detect(self, conn) -> Optional[str]:
        """综合检测当前版本

        版本探测优先级：
        1. 数据库记录（schema_migrations.target_version）- 反映实际部署状态
        2. git 检测 - 仅作为辅助提示

        如果数据库记录和 git 检测结果不一致，以数据库为准，因为数据库状态
        反映的是实际部署的版本，而 git 分支可能已经被切到了新版本。
        """
        db_version = self.detect_from_db(conn)
        git_version = self.detect_from_git()

        if db_version:
            if git_version and git_version != db_version:
                log_info(f"git 当前分支对应版本: {git_version}，数据库实际版本: {db_version}")
                log_info("以数据库记录为准")
            return db_version

        if git_version:
            log_info(f"数据库无迁移记录，git 检测到版本: {git_version}")
            return git_version

        return None


class MigrationPlanner:
    """迁移计划生成器"""

    def __init__(self, manifest_path: Path, migrations_dir: Path):
        self.manifest_path = manifest_path
        self.migrations_dir = migrations_dir
        self.versions = self._load_versions()

    def _load_versions(self) -> List[Dict]:
        """加载版本清单"""
        if not self.manifest_path.exists():
            return []
        with open(self.manifest_path, "r", encoding="utf-8") as f:
            data = json.load(f)
        return data.get("versions", [])

    def get_all_version_tags(self) -> List[str]:
        """获取所有版本 tag 列表"""
        return [v["tag"] for v in self.versions]

    def plan(self, from_version: str, to_version: str) -> Tuple[List[Migration], List[Dict]]:
        """
        生成迁移计划
        返回: (迁移脚本列表, 版本变更信息列表)
        """
        migrations = []
        version_changes = []

        from_idx = -1
        to_idx = -1
        for i, v in enumerate(self.versions):
            if v["tag"] == from_version:
                from_idx = i
            if v["tag"] == to_version:
                to_idx = i

        if from_idx == -1:
            raise ValueError(f"未知起始版本: {from_version}")
        if to_idx == -1:
            raise ValueError(f"未知目标版本: {to_version}")
        if from_idx >= to_idx:
            raise ValueError(f"起始版本 {from_version} 必须早于目标版本 {to_version}")

        # 收集需要执行的升级脚本
        for i in range(from_idx + 1, to_idx + 1):
            version = self.versions[i]
            version_changes.append({
                "from": self.versions[i - 1]["tag"],
                "to": version["tag"],
                "services_added": list(set(version.get("services", [])) - set(self.versions[i - 1].get("services", []))),
                "infra_changed": list(set(version.get("infra", [])) - set(self.versions[i - 1].get("infra", []))),
            })

            for upgrade_dir in version.get("upgrades", []):
                upgrade_path = self.migrations_dir / "upgrades" / upgrade_dir
                if upgrade_path.exists():
                    for filepath in sorted(upgrade_path.glob("*")):
                        migration = Migration.from_file(filepath)
                        if migration:
                            migration.module = version["tag"]
                            migrations.append(migration)

        return migrations, version_changes

    def plan_current(self, from_version: str) -> Tuple[List[Migration], List[Dict]]:
        """
        生成到当前分支最新状态的迁移计划
        不依赖 version_manifest.json，直接扫描文件系统中的所有升级脚本

        返回: (迁移脚本列表, 版本变更信息列表)
        """
        migrations = []
        version_changes = []
        seen_versions = set()

        upgrades_dir = self.migrations_dir / "upgrades"
        if not upgrades_dir.exists():
            return migrations, version_changes

        # 扫描所有版本目录，按目录名排序
        version_dirs = sorted([d for d in upgrades_dir.iterdir() if d.is_dir()], key=lambda d: d.name)

        for version_dir in version_dirs:
            version_tag = version_dir.name

            # 跳过起始版本及之前的版本
            # 找到 manifest 中 from_version 的索引，只包含之后的版本
            from_idx = -1
            for i, v in enumerate(self.versions):
                if v["tag"] == from_version:
                    from_idx = i
                    break

            # 如果该版本目录在 manifest 中存在且索引 <= from_idx，跳过
            version_in_manifest = False
            for i, v in enumerate(self.versions):
                if v["tag"] == version_tag:
                    version_in_manifest = True
                    if i <= from_idx:
                        continue
                    break

            # 如果目录名不在 manifest 中（开发中的新版本），也包含进来
            if version_in_manifest:
                for i, v in enumerate(self.versions):
                    if v["tag"] == version_tag and i > from_idx:
                        if version_tag not in seen_versions:
                            seen_versions.add(version_tag)
                            prev_version = self.versions[i - 1]["tag"]
                            version_changes.append({
                                "from": prev_version,
                                "to": version_tag,
                                "services_added": list(set(v.get("services", [])) - set(self.versions[i - 1].get("services", []))),
                                "infra_changed": list(set(v.get("infra", [])) - set(self.versions[i - 1].get("infra", []))),
                            })
                        break
            else:
                # 开发中的版本，不在 manifest 中
                if version_tag not in seen_versions:
                    seen_versions.add(version_tag)
                    version_changes.append({
                        "from": from_version if not version_changes else version_changes[-1]["to"],
                        "to": version_tag,
                        "services_added": [],
                        "infra_changed": [],
                        "note": "开发中版本，尚未发布"
                    })

            # 收集该版本目录下的所有脚本
            for filepath in sorted(version_dir.glob("*")):
                migration = Migration.from_file(filepath)
                if migration:
                    migration.module = version_tag
                    migrations.append(migration)

        return migrations, version_changes


class MigrationManager:
    """迁移管理器"""

    def __init__(self, db_config: DatabaseConfig, migrations_dir: Path, manifest_path: Path):
        self.db_config = db_config
        self.migrations_dir = migrations_dir
        self.manifest_path = manifest_path
        self.conn = None
        self.planner = MigrationPlanner(manifest_path, migrations_dir)
        self.version_detector = VersionDetector(manifest_path)

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
        """确保迁移跟踪表存在（v2 扩展表结构）"""
        create_sql = """
        CREATE TABLE IF NOT EXISTS schema_migrations (
            id SERIAL PRIMARY KEY,
            version VARCHAR(64) NOT NULL,
            description TEXT,
            filename VARCHAR(256) NOT NULL,
            checksum VARCHAR(64),
            executed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            execution_time_ms INTEGER,
            executed_by VARCHAR(128),
            success BOOLEAN DEFAULT TRUE,
            target_version VARCHAR(32),
            module VARCHAR(32),
            migration_type VARCHAR(16) DEFAULT 'sql'
        );
        CREATE UNIQUE INDEX IF NOT EXISTS idx_schema_migrations_version
            ON schema_migrations(version, module, migration_type);
        CREATE INDEX IF NOT EXISTS idx_schema_migrations_executed_at
            ON schema_migrations(executed_at);
        """
        with self.conn.cursor() as cur:
            cur.execute(create_sql)
            self.conn.commit()

    def get_executed_migrations(self) -> dict:
        """获取已执行的迁移"""
        with self.conn.cursor() as cur:
            cur.execute("""
                SELECT version, checksum, executed_at, success, target_version, module, migration_type
                FROM schema_migrations
                ORDER BY version
            """)
            rows = cur.fetchall()
            return {
                f"{row[0]}:{row[5]}:{row[6]}": {
                    "checksum": row[1], "executed_at": row[2], "success": row[3],
                    "target_version": row[4], "module": row[5], "migration_type": row[6]
                }
                for row in rows
            }

    def is_migration_executed(self, migration: Migration) -> bool:
        """检查迁移是否已执行"""
        key = f"{migration.version}:{migration.module}:{migration.migration_type}"
        executed = self.get_executed_migrations()
        return key in executed

    def execute_sql_migration(self, migration: Migration, target_version: str) -> bool:
        """执行 SQL 迁移"""
        log_step(f"执行 SQL 迁移 {migration.version}: {migration.description}")

        with open(migration.filepath, "r", encoding="utf-8") as f:
            sql_content = f.read()

        start_time = time.time()
        executed_by = os.environ.get("USER") or os.environ.get("USERNAME") or "unknown"

        try:
            with self.conn.cursor() as cur:
                cur.execute(sql_content)
                cur.execute("""
                    INSERT INTO schema_migrations
                    (version, description, filename, checksum, execution_time_ms, executed_by, success, target_version, module, migration_type)
                    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                    ON CONFLICT (version, module, migration_type) DO NOTHING
                """, (
                    migration.version, migration.description, migration.filename,
                    migration.checksum, int((time.time() - start_time) * 1000),
                    executed_by, True, target_version, migration.module, "sql"
                ))
                self.conn.commit()
                log_success(f"迁移 {migration.version} 执行成功")
                return True
        except Exception as e:
            self.conn.rollback()
            log_error(f"迁移 {migration.version} 执行失败: {e}")
            return False

    def execute_python_migration(self, migration: Migration, target_version: str) -> bool:
        """执行 Python 迁移脚本"""
        log_step(f"执行配置检查 {migration.version}: {migration.description}")

        start_time = time.time()
        executed_by = os.environ.get("USER") or os.environ.get("USERNAME") or "unknown"

        try:
            result = subprocess.run(
                [sys.executable, str(migration.filepath)],
                capture_output=True, text=True, timeout=60
            )
            print(result.stdout)
            if result.stderr:
                print(result.stderr, file=sys.stderr)

            success = result.returncode == 0

            with self.conn.cursor() as cur:
                cur.execute("""
                    INSERT INTO schema_migrations
                    (version, description, filename, checksum, execution_time_ms, executed_by, success, target_version, module, migration_type)
                    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                    ON CONFLICT (version, module, migration_type) DO NOTHING
                """, (
                    migration.version, migration.description, migration.filename,
                    migration.checksum, int((time.time() - start_time) * 1000),
                    executed_by, success, target_version, migration.module, "python"
                ))
                self.conn.commit()

            if success:
                log_success(f"配置检查 {migration.version} 通过")
            else:
                log_warning(f"配置检查 {migration.version} 发现需要手动处理的问题")
            return success
        except Exception as e:
            log_error(f"配置检查 {migration.version} 执行失败: {e}")
            return False

    def execute_migration(self, migration: Migration, target_version: str) -> bool:
        """执行单个迁移"""
        if migration.migration_type == "py":
            return self.execute_python_migration(migration, target_version)
        else:
            return self.execute_sql_migration(migration, target_version)

    def status(self):
        """显示迁移状态"""
        print("\n" + "=" * 70)
        print("数据库迁移状态")
        print("=" * 70)
        print(f"数据库: {self.db_config.host}:{self.db_config.port}/{self.db_config.name}")
        print(f"迁移目录: {self.migrations_dir}")

        current_version = self.version_detector.detect(self.conn)
        if current_version:
            log_info(f"当前版本: {current_version}")
        else:
            log_warning("无法检测当前版本")

        print("=" * 70)

        # 列出所有已知版本
        all_tags = self.planner.get_all_version_tags()
        if all_tags:
            print(f"\n已知版本: {', '.join(all_tags)}")
        else:
            print("\n未找到版本清单")

        print("=" * 70 + "\n")

    def plan(self, from_version: str, to_version: str):
        """显示迁移计划"""
        try:
            if to_version == "current":
                migrations, version_changes = self.planner.plan_current(from_version)
            else:
                migrations, version_changes = self.planner.plan(from_version, to_version)
        except ValueError as e:
            log_error(str(e))
            return False

        print("\n" + "=" * 70)
        if to_version == "current":
            print(f"迁移计划: {from_version} -> current（当前分支最新状态）")
        else:
            print(f"迁移计划: {from_version} -> {to_version}")
        print("=" * 70)

        if version_changes:
            print("\n【版本变更概览】")
            for change in version_changes:
                note = change.get("note", "")
                print(f"  {change['from']} -> {change['to']}" + (f" [{note}]" if note else ""))
                if change.get("services_added"):
                    print(f"    新增服务: {', '.join(change['services_added'])}")
                if change.get("infra_changed"):
                    print(f"    基础设施变更: {', '.join(change['infra_changed'])}")

        if migrations:
            print(f"\n【待执行脚本】共 {len(migrations)} 个")
            for m in migrations:
                status = "已执行" if self.is_migration_executed(m) else "待执行"
                print(f"  [{status}] {m.module}/{m.filename} ({m.migration_type})")
        else:
            print("\n无需执行任何迁移脚本")

        print("=" * 70 + "\n")
        return True

    def migrate(self, from_version: Optional[str], to_version: Optional[str], auto_detect: bool = False, force: bool = False) -> bool:
        """执行迁移"""
        if auto_detect:
            detected = self.version_detector.detect(self.conn)
            if detected:
                from_version = detected
                log_info(f"自动检测到当前版本: {from_version}")
            else:
                log_error("无法自动检测当前版本，请使用 --from 手动指定")
                return False

        if not from_version:
            log_error("请使用 --from 指定起始版本，或使用 --auto 自动检测")
            return False

        if not to_version:
            # 默认迁移到最新版本
            all_tags = self.planner.get_all_version_tags()
            if all_tags:
                to_version = all_tags[-1]
                log_info(f"目标版本未指定，默认迁移到最新版本: {to_version}")
            else:
                log_error("无法确定目标版本")
                return False

        if from_version == to_version:
            log_info(f"当前版本 {from_version} 已经是目标版本，无需迁移")
            return True

        try:
            if to_version == "current":
                migrations, version_changes = self.planner.plan_current(from_version)
            else:
                migrations, version_changes = self.planner.plan(from_version, to_version)
        except ValueError as e:
            log_error(str(e))
            return False

        if not migrations:
            log_success("无需执行任何迁移")
            return True

        # 过滤掉已执行的迁移
        pending = [m for m in migrations if not self.is_migration_executed(m)]
        if not pending:
            log_success("所有迁移已是最新状态，无需执行")
            return True

        # 显示迁移计划
        print("\n" + "=" * 70)
        if to_version == "current":
            print(f"迁移计划: {from_version} -> current（当前分支最新状态）")
        else:
            print(f"迁移计划: {from_version} -> {to_version}")
        print("=" * 70)

        if version_changes:
            print("\n【版本变更概览】")
            for change in version_changes:
                note = change.get("note", "")
                print(f"  {change['from']} -> {change['to']}" + (f" [{note}]" if note else ""))
                if change.get("services_added"):
                    print(f"    新增服务: {', '.join(change['services_added'])}")
                if change.get("infra_changed"):
                    print(f"    基础设施变更: {', '.join(change['infra_changed'])}")

        print(f"\n【待执行脚本】共 {len(pending)} 个")
        for m in pending:
            print(f"  {m.module}/{m.filename} ({m.migration_type})")
        print("=" * 70 + "\n")

        if not force:
            response = input("确认执行以上迁移? [y/N]: ").strip().lower()
            if response != "y":
                log_info("已取消迁移")
                return False

        print()
        success_count = 0
        fail_count = 0

        # 当目标为 current 时，使用实际版本目录名作为数据库记录的 target_version
        target_for_db = to_version if to_version != "current" else (version_changes[-1]["to"] if version_changes else "current")

        for migration in pending:
            if self.execute_migration(migration, target_for_db):
                success_count += 1
            else:
                fail_count += 1
                if migration.migration_type == "sql":
                    log_error(f"SQL 迁移 {migration.version} 失败，停止后续迁移")
                    break
                # Python 检查失败不阻塞，继续执行

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
        description="AlphaFrog 数据库迁移工具 v2",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  # 查看当前迁移状态
  python migrate/migrate.py status

  # 查看迁移计划（不执行）
  python migrate/migrate.py plan --from v0.3-phase1 --to v0.5

  # 自动检测当前版本并迁移到最新发布版本
  python migrate/migrate.py migrate --auto

  # 指定版本范围迁移
  python migrate/migrate.py migrate --from v0.2 --to v0.5

  # 迁移到当前分支最新状态（开发分支验证）
  python migrate/migrate.py migrate --from v0.5 --to current

  # 强制执行，不提示确认
  python migrate/migrate.py migrate --auto --force
        """
    )
    parser.add_argument(
        "command",
        choices=["status", "plan", "migrate"],
        help="命令: status=查看状态, plan=查看计划, migrate=执行迁移"
    )
    parser.add_argument("--config", "-c", help="配置文件路径")
    parser.add_argument("--force", "-f", action="store_true", help="强制执行，不提示确认")
    parser.add_argument("--from", dest="from_version", help="起始版本号")
    parser.add_argument("--to", dest="to_version", help="目标版本号，使用 'current' 表示当前分支最新状态")
    parser.add_argument("--auto", action="store_true", help="自动检测当前版本")
    parser.add_argument("--migrations-dir", "-d", help="迁移脚本目录路径")
    parser.add_argument("--manifest", "-m", help="版本清单文件路径")

    args = parser.parse_args()

    # 查找配置文件
    config_path = find_config_file(args.config)
    if not config_path:
        log_error("未找到配置文件，请创建 migrate/migrate_config.yml 或使用 --config 指定")
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

    # 确定迁移目录和清单文件
    migrations_dir = Path(args.migrations_dir) if args.migrations_dir else DEFAULT_MIGRATIONS_DIR
    manifest_path = Path(args.manifest) if args.manifest else DEFAULT_MANIFEST_PATH

    # 创建迁移管理器
    manager = MigrationManager(db_config, migrations_dir, manifest_path)

    # 连接数据库
    if not manager.connect():
        sys.exit(1)

    try:
        # 确保迁移表存在
        manager.ensure_migration_table()

        # 执行命令
        if args.command == "status":
            manager.status()
        elif args.command == "plan":
            if not args.from_version:
                detected = manager.version_detector.detect(manager.conn)
                if detected:
                    args.from_version = detected
                    log_info(f"自动检测到当前版本: {detected}")
                else:
                    log_error("无法检测当前版本，请使用 --from 指定")
                    sys.exit(1)
            if not args.to_version:
                all_tags = manager.planner.get_all_version_tags()
                if all_tags:
                    args.to_version = all_tags[-1]
                    log_info(f"目标版本未指定，默认使用最新发布版本: {args.to_version}")
                    log_info("如需验证当前分支最新状态，请使用 --to current")
                else:
                    log_error("无法确定目标版本，请使用 --to 指定")
                    sys.exit(1)
            success = manager.plan(args.from_version, args.to_version)
            sys.exit(0 if success else 1)
        elif args.command == "migrate":
            success = manager.migrate(
                from_version=args.from_version,
                to_version=args.to_version,
                auto_detect=args.auto,
                force=args.force
            )
            sys.exit(0 if success else 1)
    finally:
        manager.close()


if __name__ == "__main__":
    main()
