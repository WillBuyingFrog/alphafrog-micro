-- Portfolio schema migration: strategy portfolios + backtest tables (PostgreSQL)

ALTER TABLE alphafrog_portfolio
    ADD COLUMN IF NOT EXISTS portfolio_type VARCHAR(16) NOT NULL DEFAULT 'REAL'
        CHECK (portfolio_type IN ('REAL', 'STRATEGY', 'MODEL'));

ALTER TABLE alphafrog_portfolio
    ADD COLUMN IF NOT EXISTS base_currency VARCHAR(16) NOT NULL DEFAULT 'CNY';

ALTER TABLE alphafrog_portfolio
    ADD COLUMN IF NOT EXISTS benchmark_symbol VARCHAR(64);

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
