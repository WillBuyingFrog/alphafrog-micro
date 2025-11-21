-- AlphaFrog Microservice Database Index Optimization
-- 修复DAO注解与SQL注入风险相关的索引优化

-- 股票信息表索引优化
CREATE INDEX IF NOT EXISTS idx_stock_info_ts_code ON alphafrog_stock_info(ts_code);
CREATE INDEX IF NOT EXISTS idx_stock_info_name ON alphafrog_stock_info(name);
CREATE INDEX IF NOT EXISTS idx_stock_info_fullname ON alphafrog_stock_info(fullname);
CREATE INDEX IF NOT EXISTS idx_stock_info_symbol ON alphafrog_stock_info(symbol);

-- 股票日线数据表索引优化 (ts_code, trade_date组合索引)
CREATE INDEX IF NOT EXISTS idx_stock_daily_ts_code_trade_date ON alphafrog_stock_daily(ts_code, trade_date);
CREATE INDEX IF NOT EXISTS idx_stock_daily_trade_date ON alphafrog_stock_daily(trade_date);

-- 指数信息表索引优化
CREATE INDEX IF NOT EXISTS idx_index_info_ts_code ON alphafrog_index_info(ts_code);
CREATE INDEX IF NOT EXISTS idx_index_info_name ON alphafrog_index_info(name);
CREATE INDEX IF NOT EXISTS idx_index_info_fullname ON alphafrog_index_info(fullname);

-- 指数日线数据表索引优化 (ts_code, trade_date组合索引)
CREATE INDEX IF NOT EXISTS idx_index_daily_ts_code_trade_date ON alphafrog_index_daily(ts_code, trade_date);
CREATE INDEX IF NOT EXISTS idx_index_daily_trade_date ON alphafrog_index_daily(trade_date);

-- 基金信息表索引优化
CREATE INDEX IF NOT EXISTS idx_fund_info_ts_code ON alphafrog_fund_info(ts_code);
CREATE INDEX IF NOT EXISTS idx_fund_info_name ON alphafrog_fund_info(name);

-- 基金净值数据表索引优化 (ts_code, nav_date组合索引)
CREATE INDEX IF NOT EXISTS idx_fund_nav_ts_code_nav_date ON alphafrog_fund_nav(ts_code, nav_date);
CREATE INDEX IF NOT EXISTS idx_fund_nav_nav_date ON alphafrog_fund_nav(nav_date);

-- 基金持仓数据表索引优化
CREATE INDEX IF NOT EXISTS idx_fund_portfolio_ts_code_end_date ON alphafrog_fund_portfolio(ts_code, end_date);
CREATE INDEX IF NOT EXISTS idx_fund_portfolio_symbol_end_date ON alphafrog_fund_portfolio(symbol, end_date);

-- 指数权重数据表索引优化
CREATE INDEX IF NOT EXISTS idx_index_weight_index_code_trade_date ON alphafrog_index_weight(index_code, trade_date);
CREATE INDEX IF NOT EXISTS idx_index_weight_con_code_trade_date ON alphafrog_index_weight(con_code, trade_date);

-- 交易日历表索引优化
CREATE INDEX IF NOT EXISTS idx_trade_calendar_exchange_date ON alphafrog_trade_calendar(exchange, cal_date_timestamp);
CREATE INDEX IF NOT EXISTS idx_trade_calendar_is_open ON alphafrog_trade_calendar(is_open);

-- 用户表索引优化
CREATE INDEX IF NOT EXISTS idx_user_username ON alphafrog_user(username);
CREATE INDEX IF NOT EXISTS idx_user_email ON alphafrog_user(email);

-- 投资组合表索引优化
CREATE INDEX IF NOT EXISTS idx_portfolio_user_id ON portfolio(user_id);

-- 投资组合持仓表索引优化
CREATE INDEX IF NOT EXISTS idx_portfolio_holding_portfolio_id ON portfolio_holding(portfolio_id);
CREATE INDEX IF NOT EXISTS idx_portfolio_holding_asset_identifier ON portfolio_holding(asset_identifier);

-- 分析说明:
-- 1. 所有LIKE查询使用CONCAT('%', #{param}, '%')代替'%${param}%'，避免SQL注入
-- 2. 为所有模糊查询字段建立索引，提高查询性能
-- 3. ts_code和trade_date的组合索引是核心业务查询的关键
-- 4. 所有查询都添加了LIMIT/OFFSET参数，防止全表扫描