-- 境内股票、基金、指数数据表（从零初始化）

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

-- 索引

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
