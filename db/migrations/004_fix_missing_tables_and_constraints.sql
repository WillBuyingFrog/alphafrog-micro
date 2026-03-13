-- =============================================
-- PR #35 修复：补充缺失的表和约束调整
-- =============================================

-- 1. 创建缺失的中信行业日线表 (CiIndustryDaily)
-- TuShare 接口: ci_daily
CREATE TABLE IF NOT EXISTS alphafrog_index_ci_daily (
    id BIGSERIAL PRIMARY KEY,
    ts_code VARCHAR(32) NOT NULL,
    trade_date BIGINT NOT NULL,
    name VARCHAR(128),
    open DOUBLE PRECISION,
    low DOUBLE PRECISION,
    high DOUBLE PRECISION,
    close DOUBLE PRECISION,
    pre_close DOUBLE PRECISION,
    change_val DOUBLE PRECISION,
    pct_change DOUBLE PRECISION,
    vol DOUBLE PRECISION,
    amount DOUBLE PRECISION,
    extended JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (ts_code, trade_date)
);

CREATE INDEX IF NOT EXISTS idx_ci_daily_ts_code ON alphafrog_index_ci_daily(ts_code);
CREATE INDEX IF NOT EXISTS idx_ci_daily_trade_date ON alphafrog_index_ci_daily(trade_date);

-- 2. 修复 stock_report_rc 表的 quarter 字段约束
-- 问题：TuShare 返回的数据中 quarter 可能为 null，但原表设计为 NOT NULL
-- 解决：改为允许 NULL
ALTER TABLE alphafrog_stock_report_rc 
    ALTER COLUMN quarter DROP NOT NULL;

-- 3. 为新增表添加更新时间触发器
DO $$
DECLARE
    table_name text;
    table_list text[] := ARRAY[
        'alphafrog_index_ci_daily'
    ];
BEGIN
    FOREACH table_name IN ARRAY table_list
    LOOP
        -- 检查表和触发器是否已存在
        IF to_regclass(table_name) IS NOT NULL THEN
            IF NOT EXISTS(
                SELECT 1 FROM pg_trigger 
                WHERE tgname = 'trg_' || table_name || '_updated_at'
                AND tgrelid = to_regclass(table_name)
            ) THEN
                EXECUTE format('CREATE TRIGGER trg_%s_updated_at 
                               BEFORE UPDATE ON %s 
                               FOR EACH ROW EXECUTE FUNCTION update_updated_at_column()',
                               table_name, table_name);
            END IF;
        END IF;
    END LOOP;
END $$;
