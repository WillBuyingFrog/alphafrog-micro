-- Portfolio service DDL (PostgreSQL)
-- 2 位小数的金额/数量字段，表名前缀 alphafrog_

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

CREATE INDEX IF NOT EXISTS idx_strategy_definition_portfolio ON alphafrog_strategy_definition (portfolio_id);
CREATE INDEX IF NOT EXISTS idx_strategy_definition_user ON alphafrog_strategy_definition (user_id);

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

CREATE INDEX IF NOT EXISTS idx_strategy_target_strategy ON alphafrog_strategy_target (strategy_id);
CREATE INDEX IF NOT EXISTS idx_strategy_target_symbol ON alphafrog_strategy_target (symbol);

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

CREATE INDEX IF NOT EXISTS idx_strategy_backtest_strategy ON alphafrog_strategy_backtest_run (strategy_id);
CREATE INDEX IF NOT EXISTS idx_strategy_backtest_user ON alphafrog_strategy_backtest_run (user_id);

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

CREATE INDEX IF NOT EXISTS idx_strategy_nav_run ON alphafrog_strategy_nav (run_id);
CREATE INDEX IF NOT EXISTS idx_strategy_nav_date ON alphafrog_strategy_nav (trade_date);
