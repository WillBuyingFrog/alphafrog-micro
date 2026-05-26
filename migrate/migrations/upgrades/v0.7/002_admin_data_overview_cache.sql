-- admin 数据概览缓存表
CREATE TABLE IF NOT EXISTS alphafrog_admin_data_overview_cache (
    id BIGSERIAL PRIMARY KEY,
    fund_count BIGINT DEFAULT 0,
    index_count BIGINT DEFAULT 0,
    stock_count BIGINT DEFAULT 0,
    fund_nav_count BIGINT DEFAULT 0,
    index_daily_count BIGINT DEFAULT 0,
    stock_daily_count BIGINT DEFAULT 0,
    cached_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_admin_data_overview_cache_cached_at ON alphafrog_admin_data_overview_cache(cached_at DESC);

-- 初始化一条空记录
INSERT INTO alphafrog_admin_data_overview_cache (
    fund_count, index_count, stock_count, fund_nav_count, index_daily_count, stock_daily_count,
    cached_at, created_at, updated_at
) VALUES (0, 0, 0, 0, 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;
