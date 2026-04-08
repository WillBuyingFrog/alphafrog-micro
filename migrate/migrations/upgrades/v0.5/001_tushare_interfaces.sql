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

-- =============================================
-- PR #35 补充：TuShare 股票相关接口（9个）
-- 云端agent漏做的部分
-- =============================================

-- 1. 利润表 (StockIncome)
CREATE TABLE IF NOT EXISTS alphafrog_stock_income (
    id BIGSERIAL PRIMARY KEY,
    ts_code VARCHAR(32) NOT NULL,
    ann_date BIGINT,
    f_ann_date BIGINT,
    end_date BIGINT NOT NULL,
    report_type VARCHAR(8),
    comp_type VARCHAR(8),
    end_type VARCHAR(8),
    basic_eps DOUBLE PRECISION,
    diluted_eps DOUBLE PRECISION,
    total_revenue DOUBLE PRECISION,
    revenue DOUBLE PRECISION,
    total_cogs DOUBLE PRECISION,
    operate_profit DOUBLE PRECISION,
    total_profit DOUBLE PRECISION,
    n_income DOUBLE PRECISION,
    n_income_attr_p DOUBLE PRECISION,
    ebit DOUBLE PRECISION,
    ebitda DOUBLE PRECISION,
    rd_exp DOUBLE PRECISION,
    update_flag VARCHAR(8),
    extended JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (ts_code, end_date, report_type)
);

CREATE INDEX IF NOT EXISTS idx_stock_income_ts_code ON alphafrog_stock_income(ts_code);
CREATE INDEX IF NOT EXISTS idx_stock_income_end_date ON alphafrog_stock_income(end_date);

-- 2. 资产负债表 (StockBalancesheet)
CREATE TABLE IF NOT EXISTS alphafrog_stock_balancesheet (
    id BIGSERIAL PRIMARY KEY,
    ts_code VARCHAR(32) NOT NULL,
    ann_date BIGINT,
    f_ann_date BIGINT,
    end_date BIGINT NOT NULL,
    report_type VARCHAR(8),
    comp_type VARCHAR(8),
    end_type VARCHAR(8),
    -- 流动资产
    money_cap DOUBLE PRECISION,
    accounts_receiv DOUBLE PRECISION,
    inventories DOUBLE PRECISION,
    total_cur_assets DOUBLE PRECISION,
    -- 非流动资产
    fix_assets DOUBLE PRECISION,
    goodwill DOUBLE PRECISION,
    intan_assets DOUBLE PRECISION,
    r_and_d DOUBLE PRECISION,
    total_nca DOUBLE PRECISION,
    total_assets DOUBLE PRECISION,
    -- 流动负债
    st_borr DOUBLE PRECISION,
    acct_payable DOUBLE PRECISION,
    total_cur_liab DOUBLE PRECISION,
    -- 非流动负债
    lt_borr DOUBLE PRECISION,
    bond_payable DOUBLE PRECISION,
    total_ncl DOUBLE PRECISION,
    total_liab DOUBLE PRECISION,
    -- 股东权益
    total_hldr_eqy_exc_min_int DOUBLE PRECISION,
    total_hldr_eqy_inc_min_int DOUBLE PRECISION,
    minority_int DOUBLE PRECISION,
    update_flag VARCHAR(8),
    extended JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (ts_code, end_date, report_type)
);

CREATE INDEX IF NOT EXISTS idx_stock_balancesheet_ts_code ON alphafrog_stock_balancesheet(ts_code);
CREATE INDEX IF NOT EXISTS idx_stock_balancesheet_end_date ON alphafrog_stock_balancesheet(end_date);

-- 3. 现金流量表 (StockCashflow)
CREATE TABLE IF NOT EXISTS alphafrog_stock_cashflow (
    id BIGSERIAL PRIMARY KEY,
    ts_code VARCHAR(32) NOT NULL,
    ann_date BIGINT,
    f_ann_date BIGINT,
    end_date BIGINT NOT NULL,
    comp_type VARCHAR(8),
    report_type VARCHAR(8),
    end_type VARCHAR(8),
    -- 经营活动
    c_fr_sale_sg DOUBLE PRECISION,
    n_cashflow_act DOUBLE PRECISION,
    -- 投资活动
    n_cashflow_inv_act DOUBLE PRECISION,
    -- 筹资活动
    n_cash_flows_fnc_act DOUBLE PRECISION,
    -- 汇总
    free_cashflow DOUBLE PRECISION,
    c_cash_equ_end_period DOUBLE PRECISION,
    n_incr_cash_cash_equ DOUBLE PRECISION,
    update_flag VARCHAR(8),
    extended JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (ts_code, end_date, report_type)
);

CREATE INDEX IF NOT EXISTS idx_stock_cashflow_ts_code ON alphafrog_stock_cashflow(ts_code);
CREATE INDEX IF NOT EXISTS idx_stock_cashflow_end_date ON alphafrog_stock_cashflow(end_date);

-- 4. 业绩预告 (StockForecast)
CREATE TABLE IF NOT EXISTS alphafrog_stock_forecast (
    id BIGSERIAL PRIMARY KEY,
    ts_code VARCHAR(32) NOT NULL,
    ann_date BIGINT NOT NULL,
    end_date BIGINT,
    type VARCHAR(32),
    p_change_min DOUBLE PRECISION,
    p_change_max DOUBLE PRECISION,
    net_profit_min DOUBLE PRECISION,
    net_profit_max DOUBLE PRECISION,
    last_parent_net DOUBLE PRECISION,
    first_ann_date BIGINT,
    summary TEXT,
    change_reason TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (ts_code, ann_date, end_date)
);

CREATE INDEX IF NOT EXISTS idx_stock_forecast_ts_code ON alphafrog_stock_forecast(ts_code);
CREATE INDEX IF NOT EXISTS idx_stock_forecast_ann_date ON alphafrog_stock_forecast(ann_date);

-- 5. 业绩快报 (StockExpress)
CREATE TABLE IF NOT EXISTS alphafrog_stock_express (
    id BIGSERIAL PRIMARY KEY,
    ts_code VARCHAR(32) NOT NULL,
    ann_date BIGINT,
    end_date BIGINT,
    revenue DOUBLE PRECISION,
    operate_profit DOUBLE PRECISION,
    total_profit DOUBLE PRECISION,
    n_income DOUBLE PRECISION,
    total_assets DOUBLE PRECISION,
    total_hldr_eqy_exc_min_int DOUBLE PRECISION,
    diluted_eps DOUBLE PRECISION,
    diluted_roe DOUBLE PRECISION,
    yoy_net_profit DOUBLE PRECISION,
    bps DOUBLE PRECISION,
    yoy_sales DOUBLE PRECISION,
    yoy_op DOUBLE PRECISION,
    yoy_tp DOUBLE PRECISION,
    yoy_dedu_np DOUBLE PRECISION,
    yoy_eps DOUBLE PRECISION,
    yoy_roe DOUBLE PRECISION,
    perf_summary TEXT,
    is_audit INTEGER,
    remark TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (ts_code, ann_date, end_date)
);

CREATE INDEX IF NOT EXISTS idx_stock_express_ts_code ON alphafrog_stock_express(ts_code);
CREATE INDEX IF NOT EXISTS idx_stock_express_ann_date ON alphafrog_stock_express(ann_date);

-- 6. 卖方盈利预测 (StockReportRc)
CREATE TABLE IF NOT EXISTS alphafrog_stock_report_rc (
    id BIGSERIAL PRIMARY KEY,
    ts_code VARCHAR(32) NOT NULL,
    name VARCHAR(128),
    report_date BIGINT NOT NULL,
    report_title VARCHAR(512),
    report_type VARCHAR(64),
    classify VARCHAR(64),
    org_name VARCHAR(256) NOT NULL,
    author_name VARCHAR(128),
    quarter VARCHAR(16) NOT NULL,
    op_rt DOUBLE PRECISION,
    op_pr DOUBLE PRECISION,
    tp DOUBLE PRECISION,
    np DOUBLE PRECISION,
    eps DOUBLE PRECISION,
    pe DOUBLE PRECISION,
    rd DOUBLE PRECISION,
    roe DOUBLE PRECISION,
    ev_ebitda DOUBLE PRECISION,
    rating VARCHAR(32),
    max_price DOUBLE PRECISION,
    min_price DOUBLE PRECISION,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (ts_code, report_date, org_name, quarter)
);

CREATE INDEX IF NOT EXISTS idx_stock_report_rc_ts_code ON alphafrog_stock_report_rc(ts_code);
CREATE INDEX IF NOT EXISTS idx_stock_report_rc_report_date ON alphafrog_stock_report_rc(report_date);

-- 7. 个股资金流向 (StockMoneyflow)
CREATE TABLE IF NOT EXISTS alphafrog_stock_moneyflow (
    id BIGSERIAL PRIMARY KEY,
    ts_code VARCHAR(32) NOT NULL,
    trade_date BIGINT NOT NULL,
    -- 小单
    buy_sm_vol BIGINT,
    buy_sm_amount DOUBLE PRECISION,
    sell_sm_vol BIGINT,
    sell_sm_amount DOUBLE PRECISION,
    -- 中单
    buy_md_vol BIGINT,
    buy_md_amount DOUBLE PRECISION,
    sell_md_vol BIGINT,
    sell_md_amount DOUBLE PRECISION,
    -- 大单
    buy_lg_vol BIGINT,
    buy_lg_amount DOUBLE PRECISION,
    sell_lg_vol BIGINT,
    sell_lg_amount DOUBLE PRECISION,
    -- 特大单
    buy_elg_vol BIGINT,
    buy_elg_amount DOUBLE PRECISION,
    sell_elg_vol BIGINT,
    sell_elg_amount DOUBLE PRECISION,
    -- 净流入
    net_mf_vol BIGINT,
    net_mf_amount DOUBLE PRECISION,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (ts_code, trade_date)
);

CREATE INDEX IF NOT EXISTS idx_stock_moneyflow_ts_code ON alphafrog_stock_moneyflow(ts_code);
CREATE INDEX IF NOT EXISTS idx_stock_moneyflow_trade_date ON alphafrog_stock_moneyflow(trade_date);

-- 8. 前十大股东 (StockTop10Holders)
CREATE TABLE IF NOT EXISTS alphafrog_stock_top10_holders (
    id BIGSERIAL PRIMARY KEY,
    ts_code VARCHAR(32) NOT NULL,
    ann_date BIGINT,
    end_date BIGINT NOT NULL,
    holder_name VARCHAR(512) NOT NULL,
    hold_amount DOUBLE PRECISION,
    hold_ratio DOUBLE PRECISION,
    hold_float_ratio DOUBLE PRECISION,
    hold_change DOUBLE PRECISION,
    holder_type VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (ts_code, end_date, holder_name)
);

CREATE INDEX IF NOT EXISTS idx_stock_top10_holders_ts_code ON alphafrog_stock_top10_holders(ts_code);
CREATE INDEX IF NOT EXISTS idx_stock_top10_holders_end_date ON alphafrog_stock_top10_holders(end_date);

-- 9. 限售股解禁 (StockShareFloat)
CREATE TABLE IF NOT EXISTS alphafrog_stock_share_float (
    id BIGSERIAL PRIMARY KEY,
    ts_code VARCHAR(32) NOT NULL,
    ann_date BIGINT,
    float_date BIGINT NOT NULL,
    float_share DOUBLE PRECISION,
    float_ratio DOUBLE PRECISION,
    holder_name VARCHAR(512) NOT NULL,
    share_type VARCHAR(128) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (ts_code, float_date, holder_name, share_type)
);

CREATE INDEX IF NOT EXISTS idx_stock_share_float_ts_code ON alphafrog_stock_share_float(ts_code);
CREATE INDEX IF NOT EXISTS idx_stock_share_float_float_date ON alphafrog_stock_share_float(float_date);

-- 为所有新表添加更新时间触发器
DO $$
DECLARE
    table_name text;
    table_list text[] := ARRAY[
        'alphafrog_stock_income',
        'alphafrog_stock_balancesheet',
        'alphafrog_stock_cashflow',
        'alphafrog_stock_forecast',
        'alphafrog_stock_express',
        'alphafrog_stock_report_rc',
        'alphafrog_stock_moneyflow',
        'alphafrog_stock_top10_holders',
        'alphafrog_stock_share_float'
    ];
BEGIN
    FOREACH table_name IN ARRAY table_list
    LOOP
        -- 检查表和触发器是否已存在（使用 to_regclass 避免表不存在时报错）
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
