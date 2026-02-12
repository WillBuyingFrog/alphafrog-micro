-- AlphaFrog v0.3 全量 PostgreSQL 建表脚本（从零初始化）
-- 用法示例：
--   psql -h <host> -U <user> -d <db> -f alphafrog_schema_full.sql

BEGIN;

-- =========================
-- 1) Domestic + User tables
-- =========================

CREATE TABLE IF NOT EXISTS alphafrog_stock_info (
    id BIGSERIAL PRIMARY KEY,
    ts_code VARCHAR(64) NOT NULL,
    symbol VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    area VARCHAR(128),
    industry VARCHAR(128) NOT NULL,
    fullname VARCHAR(255),
    enname VARCHAR(255),
    cnspell VARCHAR(128),
    market VARCHAR(32) NOT NULL,
    exchange VARCHAR(32),
    curr_type VARCHAR(32),
    list_status VARCHAR(16),
    list_date BIGINT NOT NULL,
    delist_date BIGINT,
    is_hs VARCHAR(16),
    act_name VARCHAR(255),
    act_ent_type VARCHAR(255),
    UNIQUE (ts_code, symbol)
);

CREATE TABLE IF NOT EXISTS alphafrog_stock_daily (
    id BIGSERIAL PRIMARY KEY,
    ts_code VARCHAR(64) NOT NULL,
    trade_date BIGINT NOT NULL,
    close DOUBLE PRECISION,
    open DOUBLE PRECISION,
    high DOUBLE PRECISION,
    low DOUBLE PRECISION,
    pre_close DOUBLE PRECISION,
    change DOUBLE PRECISION,
    pct_chg DOUBLE PRECISION,
    vol DOUBLE PRECISION,
    amount DOUBLE PRECISION,
    UNIQUE (ts_code, trade_date)
);

CREATE TABLE IF NOT EXISTS alphafrog_index_info (
    id BIGSERIAL PRIMARY KEY,
    ts_code VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    fullname VARCHAR(255),
    market VARCHAR(64) NOT NULL,
    publisher VARCHAR(255),
    index_type VARCHAR(128),
    category VARCHAR(128),
    base_date BIGINT,
    base_point DOUBLE PRECISION,
    list_date BIGINT,
    weight_rule VARCHAR(255),
    "desc" TEXT,
    exp_date BIGINT,
    UNIQUE (ts_code)
);

CREATE TABLE IF NOT EXISTS alphafrog_index_daily (
    id BIGSERIAL PRIMARY KEY,
    ts_code VARCHAR(64) NOT NULL,
    trade_date BIGINT NOT NULL,
    close DOUBLE PRECISION,
    open DOUBLE PRECISION,
    high DOUBLE PRECISION,
    low DOUBLE PRECISION,
    pre_close DOUBLE PRECISION,
    change DOUBLE PRECISION,
    pct_chg DOUBLE PRECISION,
    vol DOUBLE PRECISION,
    amount DOUBLE PRECISION,
    UNIQUE (ts_code, trade_date)
);

CREATE TABLE IF NOT EXISTS alphafrog_index_weekly (
    id BIGSERIAL PRIMARY KEY,
    ts_code VARCHAR(64) NOT NULL,
    trade_date BIGINT NOT NULL,
    close DOUBLE PRECISION,
    open DOUBLE PRECISION,
    high DOUBLE PRECISION,
    low DOUBLE PRECISION,
    pre_close DOUBLE PRECISION,
    change DOUBLE PRECISION,
    pct_chg DOUBLE PRECISION,
    vol DOUBLE PRECISION,
    amount DOUBLE PRECISION,
    UNIQUE (ts_code, trade_date)
);

CREATE TABLE IF NOT EXISTS alphafrog_index_weight (
    id BIGSERIAL PRIMARY KEY,
    index_code VARCHAR(64),
    con_code VARCHAR(64),
    trade_date BIGINT,
    weight DOUBLE PRECISION,
    UNIQUE (index_code, con_code, trade_date)
);

CREATE TABLE IF NOT EXISTS alphafrog_fund_info (
    id BIGSERIAL PRIMARY KEY,
    ts_code VARCHAR(64) NOT NULL,
    name VARCHAR(255),
    management VARCHAR(255),
    custodian VARCHAR(255),
    fund_type VARCHAR(128),
    found_date BIGINT,
    due_date BIGINT,
    list_date BIGINT,
    issue_date BIGINT,
    delist_date BIGINT,
    issue_amount DOUBLE PRECISION,
    m_fee DOUBLE PRECISION,
    c_fee DOUBLE PRECISION,
    duration_year DOUBLE PRECISION,
    p_value DOUBLE PRECISION,
    min_amount DOUBLE PRECISION,
    exp_return DOUBLE PRECISION,
    benchmark VARCHAR(500),
    status VARCHAR(2),
    invest_type VARCHAR(128),
    type VARCHAR(10),
    trustee VARCHAR(20),
    purc_startdate BIGINT,
    redm_startdate BIGINT,
    market VARCHAR(2),
    UNIQUE (ts_code)
);

CREATE TABLE IF NOT EXISTS alphafrog_fund_nav (
    id BIGSERIAL PRIMARY KEY,
    ts_code VARCHAR(64) NOT NULL,
    ann_date BIGINT,
    nav_date BIGINT NOT NULL,
    unit_nav DOUBLE PRECISION,
    accum_nav DOUBLE PRECISION,
    accum_div DOUBLE PRECISION,
    net_asset DOUBLE PRECISION,
    total_net_asset DOUBLE PRECISION,
    adj_nav DOUBLE PRECISION,
    UNIQUE (ts_code, nav_date)
);

CREATE TABLE IF NOT EXISTS alphafrog_fund_portfolio (
    id BIGSERIAL PRIMARY KEY,
    ts_code VARCHAR(64) NOT NULL,
    ann_date BIGINT NOT NULL,
    end_date BIGINT,
    symbol VARCHAR(32) NOT NULL,
    mkv DOUBLE PRECISION,
    amount DOUBLE PRECISION,
    stk_mkv_ratio DOUBLE PRECISION,
    stk_float_ratio DOUBLE PRECISION,
    UNIQUE (ts_code, symbol, ann_date)
);

CREATE TABLE IF NOT EXISTS alphafrog_trade_calendar (
    id BIGSERIAL PRIMARY KEY,
    exchange VARCHAR(32),
    cal_date_timestamp BIGINT,
    is_open INTEGER,
    pre_trade_date_timestamp BIGINT,
    UNIQUE (exchange, cal_date_timestamp)
);

CREATE TABLE IF NOT EXISTS alphafrog_user (
    user_id BIGSERIAL PRIMARY KEY,
    username VARCHAR(128) NOT NULL,
    password VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    register_time BIGINT NOT NULL,
    user_type INTEGER,
    user_level INTEGER,
    credit INTEGER,
    UNIQUE (username),
    UNIQUE (email)
);

CREATE TABLE IF NOT EXISTS alphafrog_user_invite_code (
    id BIGSERIAL PRIMARY KEY,
    invite_code VARCHAR(64) NOT NULL UNIQUE,
    created_by BIGINT,
    used_by BIGINT,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ext JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ,
    used_at TIMESTAMPTZ
);

-- ==================
-- 2) Portfolio tables
-- ==================

CREATE TABLE IF NOT EXISTS alphafrog_portfolio (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    visibility VARCHAR(32) NOT NULL DEFAULT 'private' CHECK (visibility IN ('private', 'shared')),
    tags JSONB NOT NULL DEFAULT '[]'::jsonb,
    portfolio_type VARCHAR(16) NOT NULL DEFAULT 'REAL' CHECK (portfolio_type IN ('REAL', 'STRATEGY', 'MODEL')),
    base_currency VARCHAR(16) NOT NULL DEFAULT 'CNY',
    benchmark_symbol VARCHAR(64),
    status VARCHAR(32) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'archived')),
    timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ext JSONB NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (user_id, name, status)
);

CREATE TABLE IF NOT EXISTS alphafrog_portfolio_holding (
    id BIGSERIAL PRIMARY KEY,
    portfolio_id BIGINT NOT NULL REFERENCES alphafrog_portfolio(id) ON DELETE CASCADE,
    user_id VARCHAR(64) NOT NULL,
    symbol VARCHAR(64) NOT NULL,
    symbol_type VARCHAR(32) NOT NULL CHECK (symbol_type IN ('stock', 'etf', 'index', 'fund')),
    exchange VARCHAR(32),
    position_side VARCHAR(16) NOT NULL DEFAULT 'LONG' CHECK (position_side IN ('LONG', 'SHORT')),
    quantity NUMERIC(20, 2) NOT NULL DEFAULT 0,
    avg_cost NUMERIC(20, 2) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ext JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE IF NOT EXISTS alphafrog_portfolio_trade (
    id BIGSERIAL PRIMARY KEY,
    portfolio_id BIGINT NOT NULL REFERENCES alphafrog_portfolio(id) ON DELETE CASCADE,
    user_id VARCHAR(64) NOT NULL,
    symbol VARCHAR(64) NOT NULL,
    event_type VARCHAR(32) NOT NULL CHECK (
        event_type IN ('BUY', 'SELL', 'DIVIDEND_CASH', 'DIVIDEND_STOCK', 'SPLIT', 'FEE', 'CASH_IN', 'CASH_OUT')
    ),
    quantity NUMERIC(20, 2) NOT NULL,
    price NUMERIC(20, 2),
    fee NUMERIC(20, 2) NOT NULL DEFAULT 0,
    slippage NUMERIC(20, 2),
    trade_time TIMESTAMPTZ NOT NULL,
    settle_date TIMESTAMPTZ,
    note VARCHAR(500),
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS alphafrog_strategy_definition (
    id BIGSERIAL PRIMARY KEY,
    portfolio_id BIGINT NOT NULL REFERENCES alphafrog_portfolio(id) ON DELETE CASCADE,
    user_id VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(2000),
    rule_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    rebalance_rule VARCHAR(255),
    capital_base NUMERIC(20, 2) NOT NULL DEFAULT 0,
    start_date DATE,
    end_date DATE,
    status VARCHAR(32) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'archived')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ext JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE IF NOT EXISTS alphafrog_strategy_target (
    id BIGSERIAL PRIMARY KEY,
    strategy_id BIGINT NOT NULL REFERENCES alphafrog_strategy_definition(id) ON DELETE CASCADE,
    user_id VARCHAR(64) NOT NULL,
    symbol VARCHAR(64) NOT NULL,
    symbol_type VARCHAR(32) NOT NULL CHECK (symbol_type IN ('stock', 'etf', 'index', 'fund')),
    target_weight NUMERIC(10, 6) NOT NULL,
    effective_date DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ext JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE IF NOT EXISTS alphafrog_strategy_backtest_run (
    id BIGSERIAL PRIMARY KEY,
    strategy_id BIGINT NOT NULL REFERENCES alphafrog_strategy_definition(id) ON DELETE CASCADE,
    user_id VARCHAR(64) NOT NULL,
    run_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    start_date DATE,
    end_date DATE,
    params_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(32) NOT NULL DEFAULT 'pending',
    queued_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ext JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE IF NOT EXISTS alphafrog_strategy_nav (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES alphafrog_strategy_backtest_run(id) ON DELETE CASCADE,
    user_id VARCHAR(64) NOT NULL,
    trade_date DATE NOT NULL,
    nav NUMERIC(20, 6) NOT NULL,
    return_pct NUMERIC(10, 6) NOT NULL,
    benchmark_nav NUMERIC(20, 6),
    drawdown NUMERIC(10, 6),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ==============
-- 3) Agent tables
-- ==============

CREATE TABLE IF NOT EXISTS alphafrog_agent_run (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'RECEIVED' CHECK (status IN ('RECEIVED', 'PLANNING', 'EXECUTING', 'WAITING', 'SUMMARIZING', 'COMPLETED', 'FAILED', 'CANCELED', 'EXPIRED')),
    current_step INT NOT NULL DEFAULT 0,
    max_steps INT NOT NULL DEFAULT 12,
    plan_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    snapshot_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    last_error TEXT,
    ttl_expires_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    ext JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE IF NOT EXISTS alphafrog_agent_run_event (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL REFERENCES alphafrog_agent_run(id) ON DELETE CASCADE,
    seq INT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (run_id, seq)
);

CREATE TABLE IF NOT EXISTS alphafrog_agent_credit_application (
    id BIGSERIAL PRIMARY KEY,
    application_id VARCHAR(64) NOT NULL UNIQUE,
    user_id VARCHAR(64) NOT NULL,
    amount INTEGER NOT NULL,
    reason TEXT,
    contact VARCHAR(255),
    status VARCHAR(32) NOT NULL DEFAULT 'APPROVED',
    ext JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMPTZ
);

-- ==========
-- 4) Indexes
-- ==========

CREATE INDEX IF NOT EXISTS idx_stock_info_ts_code ON alphafrog_stock_info(ts_code);
CREATE INDEX IF NOT EXISTS idx_stock_info_name ON alphafrog_stock_info(name);
CREATE INDEX IF NOT EXISTS idx_stock_info_fullname ON alphafrog_stock_info(fullname);
CREATE INDEX IF NOT EXISTS idx_stock_info_symbol ON alphafrog_stock_info(symbol);

CREATE INDEX IF NOT EXISTS idx_stock_daily_ts_code_trade_date ON alphafrog_stock_daily(ts_code, trade_date);
CREATE INDEX IF NOT EXISTS idx_stock_daily_trade_date ON alphafrog_stock_daily(trade_date);

CREATE INDEX IF NOT EXISTS idx_index_info_ts_code ON alphafrog_index_info(ts_code);
CREATE INDEX IF NOT EXISTS idx_index_info_name ON alphafrog_index_info(name);
CREATE INDEX IF NOT EXISTS idx_index_info_fullname ON alphafrog_index_info(fullname);

CREATE INDEX IF NOT EXISTS idx_index_daily_ts_code_trade_date ON alphafrog_index_daily(ts_code, trade_date);
CREATE INDEX IF NOT EXISTS idx_index_daily_trade_date ON alphafrog_index_daily(trade_date);

CREATE INDEX IF NOT EXISTS idx_fund_info_ts_code ON alphafrog_fund_info(ts_code);
CREATE INDEX IF NOT EXISTS idx_fund_info_name ON alphafrog_fund_info(name);

CREATE INDEX IF NOT EXISTS idx_fund_nav_ts_code_nav_date ON alphafrog_fund_nav(ts_code, nav_date);
CREATE INDEX IF NOT EXISTS idx_fund_nav_nav_date ON alphafrog_fund_nav(nav_date);

CREATE INDEX IF NOT EXISTS idx_fund_portfolio_ts_code_end_date ON alphafrog_fund_portfolio(ts_code, end_date);
CREATE INDEX IF NOT EXISTS idx_fund_portfolio_symbol_end_date ON alphafrog_fund_portfolio(symbol, end_date);

CREATE INDEX IF NOT EXISTS idx_index_weight_index_code_trade_date ON alphafrog_index_weight(index_code, trade_date);
CREATE INDEX IF NOT EXISTS idx_index_weight_con_code_trade_date ON alphafrog_index_weight(con_code, trade_date);

CREATE INDEX IF NOT EXISTS idx_trade_calendar_exchange_date ON alphafrog_trade_calendar(exchange, cal_date_timestamp);
CREATE INDEX IF NOT EXISTS idx_trade_calendar_is_open ON alphafrog_trade_calendar(is_open);

CREATE INDEX IF NOT EXISTS idx_user_username ON alphafrog_user(username);
CREATE INDEX IF NOT EXISTS idx_user_email ON alphafrog_user(email);
CREATE INDEX IF NOT EXISTS idx_user_invite_code_status ON alphafrog_user_invite_code(status);
CREATE INDEX IF NOT EXISTS idx_user_invite_code_expires ON alphafrog_user_invite_code(expires_at);

CREATE INDEX IF NOT EXISTS idx_portfolio_holding_portfolio ON alphafrog_portfolio_holding(portfolio_id);
CREATE INDEX IF NOT EXISTS idx_portfolio_holding_symbol ON alphafrog_portfolio_holding(symbol);
CREATE INDEX IF NOT EXISTS idx_portfolio_trade_portfolio ON alphafrog_portfolio_trade(portfolio_id);
CREATE INDEX IF NOT EXISTS idx_portfolio_trade_symbol ON alphafrog_portfolio_trade(symbol);
CREATE INDEX IF NOT EXISTS idx_portfolio_trade_time ON alphafrog_portfolio_trade(trade_time);
CREATE INDEX IF NOT EXISTS idx_portfolio_trade_event ON alphafrog_portfolio_trade(event_type);
CREATE INDEX IF NOT EXISTS idx_strategy_definition_portfolio ON alphafrog_strategy_definition(portfolio_id);
CREATE INDEX IF NOT EXISTS idx_strategy_definition_user ON alphafrog_strategy_definition(user_id);
CREATE INDEX IF NOT EXISTS idx_strategy_target_strategy ON alphafrog_strategy_target(strategy_id);
CREATE INDEX IF NOT EXISTS idx_strategy_target_symbol ON alphafrog_strategy_target(symbol);
CREATE INDEX IF NOT EXISTS idx_strategy_backtest_strategy ON alphafrog_strategy_backtest_run(strategy_id);
CREATE INDEX IF NOT EXISTS idx_strategy_backtest_user ON alphafrog_strategy_backtest_run(user_id);
CREATE INDEX IF NOT EXISTS idx_strategy_nav_run ON alphafrog_strategy_nav(run_id);
CREATE INDEX IF NOT EXISTS idx_strategy_nav_date ON alphafrog_strategy_nav(trade_date);

CREATE INDEX IF NOT EXISTS idx_agent_run_user ON alphafrog_agent_run(user_id);
CREATE INDEX IF NOT EXISTS idx_agent_run_status ON alphafrog_agent_run(status);
CREATE INDEX IF NOT EXISTS idx_agent_run_updated ON alphafrog_agent_run(updated_at);
CREATE INDEX IF NOT EXISTS idx_agent_run_event_run ON alphafrog_agent_run_event(run_id);
CREATE INDEX IF NOT EXISTS idx_agent_credit_apply_user ON alphafrog_agent_credit_application(user_id);
CREATE INDEX IF NOT EXISTS idx_agent_credit_apply_created ON alphafrog_agent_credit_application(created_at);

COMMIT;
