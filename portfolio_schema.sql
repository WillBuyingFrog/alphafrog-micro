-- Portfolio service DDL (PostgreSQL)
-- 2 位小数的金额/数量字段，表名前缀 alphafrog_

CREATE TABLE IF NOT EXISTS alphafrog_portfolio (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    visibility VARCHAR(32) NOT NULL DEFAULT 'private' CHECK (visibility IN ('private', 'shared')),
    tags JSONB NOT NULL DEFAULT '[]'::jsonb,
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

CREATE INDEX IF NOT EXISTS idx_portfolio_holding_portfolio ON alphafrog_portfolio_holding (portfolio_id);
CREATE INDEX IF NOT EXISTS idx_portfolio_holding_symbol ON alphafrog_portfolio_holding (symbol);

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

CREATE INDEX IF NOT EXISTS idx_portfolio_trade_portfolio ON alphafrog_portfolio_trade (portfolio_id);
CREATE INDEX IF NOT EXISTS idx_portfolio_trade_symbol ON alphafrog_portfolio_trade (symbol);
CREATE INDEX IF NOT EXISTS idx_portfolio_trade_time ON alphafrog_portfolio_trade (trade_time);
CREATE INDEX IF NOT EXISTS idx_portfolio_trade_event ON alphafrog_portfolio_trade (event_type);
