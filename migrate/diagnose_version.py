#!/usr/bin/env python3
"""
诊断脚本：分析数据库状态，推断当前版本
"""

import sys
from pathlib import Path

# 添加父目录到路径以导入 migrate.py 中的模块
sys.path.insert(0, str(Path(__file__).parent))

from migrate import DatabaseConfig, find_config_file, Colors

def log_info(msg):
    print(f"{Colors.BLUE}ℹ {msg}{Colors.RESET}")

def log_success(msg):
    print(f"{Colors.GREEN}✓ {msg}{Colors.RESET}")

def log_warning(msg):
    print(f"{Colors.YELLOW}⚠ {msg}{Colors.RESET}")

def log_step(msg):
    print(f"{Colors.CYAN}→ {msg}{Colors.RESET}")

def check_table(cur, table_name):
    """检查表是否存在"""
    cur.execute("""
        SELECT EXISTS (
            SELECT FROM information_schema.tables 
            WHERE table_schema = 'public' 
            AND table_name = %s
        )
    """, (table_name,))
    return cur.fetchone()[0]

def check_column(cur, table_name, column_name):
    """检查字段是否存在"""
    cur.execute("""
        SELECT EXISTS (
            SELECT FROM information_schema.columns 
            WHERE table_schema = 'public' 
            AND table_name = %s 
            AND column_name = %s
        )
    """, (table_name, column_name))
    return cur.fetchone()[0]

def main():
    config_path = find_config_file()
    if not config_path:
        print("未找到配置文件")
        sys.exit(1)

    if config_path.name == ".env":
        db_config = DatabaseConfig.from_env(config_path)
    else:
        db_config = DatabaseConfig.from_yaml(config_path)

    import psycopg2
    try:
        conn = psycopg2.connect(db_config.to_dsn())
    except Exception as e:
        print(f"数据库连接失败: {e}")
        sys.exit(1)

    print("\n" + "=" * 60)
    print("数据库版本诊断")
    print("=" * 60)

    with conn.cursor() as cur:
        # 1. 检查 schema_migrations 表
        has_migration_table = check_table(cur, "schema_migrations")
        if has_migration_table:
            cur.execute("""
                SELECT target_version, executed_at 
                FROM schema_migrations 
                WHERE success = TRUE AND target_version IS NOT NULL
                ORDER BY executed_at DESC LIMIT 5
            """)
            rows = cur.fetchall()
            if rows:
                print("\n【迁移工具记录】")
                print(f"  最近执行的迁移目标版本:")
                for row in rows:
                    print(f"    - {row[0]} ({row[1]})")
            else:
                log_warning("schema_migrations 表存在，但无成功迁移记录")
        else:
            log_warning("未找到 schema_migrations 表（从未使用过迁移工具）")

        # 2. 通过关键表推断版本
        print("\n【通过表结构推断版本】")
        
        checks = [
            ("alphafrog_strategy_backtest_run 表存在", "alphafrog_strategy_backtest_run"),
            ("alphafrog_agent_run 表存在", "alphafrog_agent_run"),
            ("alphafrog_user_invite_code 表存在", "alphafrog_user_invite_code"),
            ("alphafrog_agent_credit_application 表存在", "alphafrog_agent_credit_application"),
            ("alphafrog_agent_run_message 表存在", "alphafrog_agent_run_message"),
            ("alphafrog_index_ci_member 表存在", "alphafrog_index_ci_member"),
            ("alphafrog_rag_announcement 表存在", "alphafrog_rag_announcement"),
        ]
        
        detected_version = "v0.2"
        for desc, table in checks:
            exists = check_table(cur, table)
            if exists:
                log_success(desc)
                if table == "alphafrog_agent_run":
                    detected_version = "v0.3-phase1"
                elif table == "alphafrog_user_invite_code":
                    detected_version = "v0.4"
                elif table == "alphafrog_index_ci_member":
                    detected_version = "v0.5"
                elif table == "alphafrog_rag_announcement":
                    detected_version = "v0.6"
            else:
                print(f"  ✗ {desc}")

        # 3. 检查特定字段
        print("\n【特定字段检查】")
        if check_table(cur, "alphafrog_strategy_backtest_run"):
            has_queued_at = check_column(cur, "alphafrog_strategy_backtest_run", "queued_at")
            if has_queued_at:
                log_success("alphafrog_strategy_backtest_run.queued_at 字段存在")
            else:
                log_warning("缺少 queued_at 字段（20160117 迁移）")
        
        if check_table(cur, "alphafrog_agent_run"):
            cur.execute("""
                SELECT conname, pg_get_constraintdef(oid) 
                FROM pg_constraint 
                WHERE conrelid = 'alphafrog_agent_run'::regclass 
                AND conname LIKE '%status%'
            """)
            constraints = cur.fetchall()
            has_expired = any('EXPIRED' in str(c) for c in constraints)
            if has_expired:
                log_success("agent_run 状态约束包含 EXPIRED")
            else:
                log_warning("agent_run 状态约束缺少 EXPIRED（20260210 迁移）")

        # 4. 最终结论
        print("\n" + "=" * 60)
        print("诊断结论")
        print("=" * 60)
        print(f"\n推断当前数据库版本: {detected_version}")
        
        if has_migration_table:
            print("\n建议：迁移工具已有使用记录，建议以 schema_migrations 表为准")
        else:
            print("\n建议：从未使用过迁移工具，建议按以下顺序执行：")
            print(f"  python migrate/migrate.py migrate --from v0.2 --to {detected_version}")
            print("  （将所有迁移脚本执行到当前实际版本，建立迁移记录）")
        
        print("\n然后可以继续升级到最新版本：")
        print("  python migrate/migrate.py migrate --from <当前版本> --to current")
        print("=" * 60 + "\n")

    conn.close()

if __name__ == "__main__":
    main()
