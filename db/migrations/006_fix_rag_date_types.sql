-- 将 RAG 表的日期列从 VARCHAR YYYYMMDD 改为 BIGINT 毫秒时间戳（Asia/Shanghai 00:00:00）
-- 与项目其他 trade_date BIGINT 列保持一致（002_tushare_new_data_interfaces.sql）

ALTER TABLE alphafrog_rag_announcement
    ALTER COLUMN ann_date TYPE BIGINT
    USING (EXTRACT(EPOCH FROM (to_timestamp(ann_date, 'YYYYMMDD') AT TIME ZONE 'Asia/Shanghai')) * 1000)::BIGINT;

ALTER TABLE alphafrog_rag_research_report
    ALTER COLUMN trade_date TYPE BIGINT
    USING (EXTRACT(EPOCH FROM (to_timestamp(trade_date, 'YYYYMMDD') AT TIME ZONE 'Asia/Shanghai')) * 1000)::BIGINT;
