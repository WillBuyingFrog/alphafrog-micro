-- =============================================
-- PR #35: TuShare 新数据爬取接口
-- 新增 9 张数据表：指数(5) + 基金(4)
-- 执行日期: 2026-03-13
-- =============================================

-- =============================================
-- 一、指数相关表（5张）
-- =============================================

-- 1. 中证指数成分股 (CiIndexMember)
-- TuShare 接口: ci_index_member
CREATE TABLE IF NOT EXISTS alphafrog_index_ci_member (
    id BIGSERIAL PRIMARY KEY,
    l1_code VARCHAR(32),
    l1_name VARCHAR(128),
    l2_code VARCHAR(32),
    l2_name VARCHAR(128),
    l3_code VARCHAR(32) NOT NULL,
    l3_name VARCHAR(128),
    ts_code VARCHAR(32) NOT NULL,
    name VARCHAR(128),
    in_date BIGINT,
    out_date BIGINT,
    is_new VARCHAR(8),
    extended JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (ts_code, l3_code, in_date)
);

CREATE INDEX IF NOT EXISTS idx_ci_member_ts_code ON alphafrog_index_ci_member(ts_code);
CREATE INDEX IF NOT EXISTS idx_ci_member_l1_code ON alphafrog_index_ci_member(l1_code);
CREATE INDEX IF NOT EXISTS idx_ci_member_l3_code ON alphafrog_index_ci_member(l3_code);

-- 2. 指数日线基础信息/估值指标 (IndexDailyBasic)
-- TuShare 接口: index_dailybasic
CREATE TABLE IF NOT EXISTS alphafrog_index_daily_basic (
    id BIGSERIAL PRIMARY KEY,
    ts_code VARCHAR(32) NOT NULL,
    trade_date BIGINT NOT NULL,
    total_mv DOUBLE PRECISION,
    float_mv DOUBLE PRECISION,
    total_share DOUBLE PRECISION,
    float_share DOUBLE PRECISION,
    free_share DOUBLE PRECISION,
    turnover_rate DOUBLE PRECISION,
    turnover_rate_f DOUBLE PRECISION,
    pe DOUBLE PRECISION,
    pe_ttm DOUBLE PRECISION,
    pb DOUBLE PRECISION,
    extended JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (ts_code, trade_date)
);

CREATE INDEX IF NOT EXISTS idx_daily_basic_ts_code ON alphafrog_index_daily_basic(ts_code);
CREATE INDEX IF NOT EXISTS idx_daily_basic_trade_date ON alphafrog_index_daily_basic(trade_date);

-- 3. 申万行业分类 (SwIndustryClassify)
-- TuShare 接口: index_classify
CREATE TABLE IF NOT EXISTS alphafrog_index_sw_classify (
    id BIGSERIAL PRIMARY KEY,
    index_code VARCHAR(32) NOT NULL,
    industry_name VARCHAR(128) NOT NULL,
    parent_code VARCHAR(32),
    level VARCHAR(8),
    industry_code VARCHAR(32),
    is_pub VARCHAR(8),
    src VARCHAR(32),
    extended JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (index_code, src)
);

CREATE INDEX IF NOT EXISTS idx_sw_classify_src ON alphafrog_index_sw_classify(src);
CREATE INDEX IF NOT EXISTS idx_sw_classify_level ON alphafrog_index_sw_classify(level);

-- 4. 申万行业日线行情 (SwIndustryDaily)
-- TuShare 接口: sw_daily
CREATE TABLE IF NOT EXISTS alphafrog_index_sw_daily (
    id BIGSERIAL PRIMARY KEY,
    ts_code VARCHAR(32) NOT NULL,
    trade_date BIGINT NOT NULL,
    name VARCHAR(128),
    open DOUBLE PRECISION,
    low DOUBLE PRECISION,
    high DOUBLE PRECISION,
    close DOUBLE PRECISION,
    change_val DOUBLE PRECISION,
    pct_change DOUBLE PRECISION,
    vol DOUBLE PRECISION,
    amount DOUBLE PRECISION,
    pe DOUBLE PRECISION,
    pb DOUBLE PRECISION,
    float_mv DOUBLE PRECISION,
    total_mv DOUBLE PRECISION,
    extended JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (ts_code, trade_date)
);

CREATE INDEX IF NOT EXISTS idx_sw_daily_ts_code ON alphafrog_index_sw_daily(ts_code);
CREATE INDEX IF NOT EXISTS idx_sw_daily_trade_date ON alphafrog_index_sw_daily(trade_date);

-- 5. 申万行业成分股 (SwIndustryMember)
-- TuShare 接口: index_member_all
CREATE TABLE IF NOT EXISTS alphafrog_index_sw_member (
    id BIGSERIAL PRIMARY KEY,
    l1_code VARCHAR(32),
    l1_name VARCHAR(128),
    l2_code VARCHAR(32),
    l2_name VARCHAR(128),
    l3_code VARCHAR(32) NOT NULL,
    l3_name VARCHAR(128),
    ts_code VARCHAR(32) NOT NULL,
    name VARCHAR(128),
    in_date BIGINT,
    out_date BIGINT,
    is_new VARCHAR(8),
    extended JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (ts_code, l3_code, in_date)
);

CREATE INDEX IF NOT EXISTS idx_sw_member_ts_code ON alphafrog_index_sw_member(ts_code);
CREATE INDEX IF NOT EXISTS idx_sw_member_l1_code ON alphafrog_index_sw_member(l1_code);
CREATE INDEX IF NOT EXISTS idx_sw_member_l3_code ON alphafrog_index_sw_member(l3_code);

-- =============================================
-- 二、基金相关表（4张）
-- =============================================

-- 6. ETF份额规模 (EtfShareSize)
-- TuShare 接口: etf_share_size
CREATE TABLE IF NOT EXISTS alphafrog_fund_etf_share_size (
    id BIGSERIAL PRIMARY KEY,
    trade_date BIGINT NOT NULL,
    ts_code VARCHAR(32) NOT NULL,
    etf_name VARCHAR(128),
    total_share DOUBLE PRECISION,
    total_size DOUBLE PRECISION,
    nav DOUBLE PRECISION,
    close DOUBLE PRECISION,
    exchange VARCHAR(16),
    extended JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (ts_code, trade_date)
);

CREATE INDEX IF NOT EXISTS idx_etf_share_ts_code ON alphafrog_fund_etf_share_size(ts_code);
CREATE INDEX IF NOT EXISTS idx_etf_share_trade_date ON alphafrog_fund_etf_share_size(trade_date);

-- 7. 基金公司信息 (FundCompany)
-- TuShare 接口: fund_company
CREATE TABLE IF NOT EXISTS alphafrog_fund_company (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(256) NOT NULL,
    shortname VARCHAR(128),
    short_enname VARCHAR(128),
    province VARCHAR(64),
    city VARCHAR(64),
    address TEXT,
    phone VARCHAR(64),
    office VARCHAR(256),
    website VARCHAR(256),
    chairman VARCHAR(128),
    manager VARCHAR(128),
    reg_capital DOUBLE PRECISION,
    setup_date BIGINT,
    end_date BIGINT,
    employees DOUBLE PRECISION,
    main_business TEXT,
    org_code VARCHAR(64),
    credit_code VARCHAR(64),
    extended JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (credit_code)
);

CREATE INDEX IF NOT EXISTS idx_fund_company_name ON alphafrog_fund_company(name);

-- 8. 基金经理信息 (FundManager)
-- TuShare 接口: fund_manager
CREATE TABLE IF NOT EXISTS alphafrog_fund_manager (
    id BIGSERIAL PRIMARY KEY,
    ts_code VARCHAR(32) NOT NULL,
    ann_date BIGINT,
    name VARCHAR(128) NOT NULL,
    gender VARCHAR(8),
    birth_year VARCHAR(16),
    edu VARCHAR(64),
    nationality VARCHAR(64),
    begin_date BIGINT NOT NULL,
    end_date BIGINT,
    resume TEXT,
    extended JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (ts_code, name, begin_date)
);

CREATE INDEX IF NOT EXISTS idx_fund_manager_ts_code ON alphafrog_fund_manager(ts_code);
CREATE INDEX IF NOT EXISTS idx_fund_manager_name ON alphafrog_fund_manager(name);

-- 9. 基金份额 (FundShare)
-- TuShare 接口: fund_share
CREATE TABLE IF NOT EXISTS alphafrog_fund_share (
    id BIGSERIAL PRIMARY KEY,
    ts_code VARCHAR(32) NOT NULL,
    trade_date BIGINT NOT NULL,
    fd_share DOUBLE PRECISION,
    extended JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (ts_code, trade_date)
);

CREATE INDEX IF NOT EXISTS idx_fund_share_ts_code ON alphafrog_fund_share(ts_code);
CREATE INDEX IF NOT EXISTS idx_fund_share_trade_date ON alphafrog_fund_share(trade_date);

-- 添加更新触发器函数（如果尚未存在）
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

-- 为所有新表添加更新时间触发器（兼容PostgreSQL 9.6+）
DO $$
DECLARE
    table_name text;
    table_list text[] := ARRAY[
        'alphafrog_index_ci_member',
        'alphafrog_index_daily_basic',
        'alphafrog_index_sw_classify',
        'alphafrog_index_sw_daily',
        'alphafrog_index_sw_member',
        'alphafrog_fund_etf_share_size',
        'alphafrog_fund_company',
        'alphafrog_fund_manager',
        'alphafrog_fund_share'
    ];
    trigger_exists boolean;
BEGIN
    FOREACH table_name IN ARRAY table_list
    LOOP
        -- 检查表和触发器是否已存在（使用 to_regclass 避免表不存在时报错）
        IF to_regclass(table_name) IS NOT NULL THEN
            SELECT EXISTS(
                SELECT 1 FROM pg_trigger 
                WHERE tgname = 'trg_' || table_name || '_updated_at'
                AND tgrelid = to_regclass(table_name)
            ) INTO trigger_exists;
            
            -- 如果不存在则创建
            IF NOT trigger_exists THEN
                EXECUTE format('CREATE TRIGGER trg_%s_updated_at 
                               BEFORE UPDATE ON %s 
                               FOR EACH ROW EXECUTE FUNCTION update_updated_at_column()',
                               table_name, table_name);
            END IF;
        END IF;
    END LOOP;
END $$;
