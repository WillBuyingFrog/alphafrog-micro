-- ETF 维表、场内日线、复权因子（TuShare: etf_basic, fund_daily, fund_adj）

CREATE TABLE IF NOT EXISTS alphafrog_domestic_etf (
    id BIGSERIAL PRIMARY KEY,
    ts_code VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(256),
    full_name VARCHAR(512),
    exchange VARCHAR(16),
    mgr_name VARCHAR(128),
    list_status VARCHAR(8),
    etf_type VARCHAR(32),
    index_code VARCHAR(32),
    index_name VARCHAR(256),
    list_date BIGINT,
    setup_date BIGINT,
    extended JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_domestic_etf_index_code ON alphafrog_domestic_etf(index_code);
CREATE INDEX IF NOT EXISTS idx_domestic_etf_list_status ON alphafrog_domestic_etf(list_status);

CREATE TABLE IF NOT EXISTS alphafrog_domestic_etf_daily (
    id BIGSERIAL PRIMARY KEY,
    ts_code VARCHAR(32) NOT NULL,
    trade_date BIGINT NOT NULL,
    open DOUBLE PRECISION,
    high DOUBLE PRECISION,
    low DOUBLE PRECISION,
    close DOUBLE PRECISION,
    pre_close DOUBLE PRECISION,
    change DOUBLE PRECISION,
    pct_chg DOUBLE PRECISION,
    vol DOUBLE PRECISION,
    amount DOUBLE PRECISION,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (ts_code, trade_date)
);

CREATE INDEX IF NOT EXISTS idx_domestic_etf_daily_ts_code ON alphafrog_domestic_etf_daily(ts_code);
CREATE INDEX IF NOT EXISTS idx_domestic_etf_daily_trade_date ON alphafrog_domestic_etf_daily(trade_date);

CREATE TABLE IF NOT EXISTS alphafrog_domestic_etf_adj_factor (
    id BIGSERIAL PRIMARY KEY,
    ts_code VARCHAR(32) NOT NULL,
    trade_date BIGINT NOT NULL,
    adj_factor DOUBLE PRECISION NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (ts_code, trade_date)
);

CREATE INDEX IF NOT EXISTS idx_domestic_etf_adj_ts_code ON alphafrog_domestic_etf_adj_factor(ts_code);
CREATE INDEX IF NOT EXISTS idx_domestic_etf_adj_trade_date ON alphafrog_domestic_etf_adj_factor(trade_date);
