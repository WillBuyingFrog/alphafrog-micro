-- 上市公司公告元数据表（存摘要级别，不含PDF全文）
CREATE TABLE IF NOT EXISTS alphafrog_rag_announcement (
    id              BIGSERIAL PRIMARY KEY,
    ts_code         VARCHAR(20)  NOT NULL,
    company_name    VARCHAR(100),
    ann_date        VARCHAR(10)  NOT NULL,  -- YYYYMMDD
    title           TEXT         NOT NULL,
    url             TEXT,                   -- TuShare 返回的原始 PDF 链接（仅用于下载，ingestion 后不再使用）
    rec_time        VARCHAR(30),            -- 发布时间字符串
    oss_url         TEXT,                   -- 本机 ingestion 脚本上传后的阿里云 OSS 全文 URL（Markdown）
    vectorized      BOOLEAN DEFAULT FALSE,  -- 是否已写入 Qdrant
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (ts_code, ann_date, title)
);

CREATE INDEX IF NOT EXISTS idx_rag_ann_date ON alphafrog_rag_announcement(ann_date);
CREATE INDEX IF NOT EXISTS idx_rag_ann_ts_code ON alphafrog_rag_announcement(ts_code);
CREATE INDEX IF NOT EXISTS idx_rag_ann_vectorized ON alphafrog_rag_announcement(vectorized) WHERE vectorized = FALSE;

-- 券商研究报告元数据表
CREATE TABLE IF NOT EXISTS alphafrog_rag_research_report (
    id              BIGSERIAL PRIMARY KEY,
    trade_date      VARCHAR(10)  NOT NULL,  -- YYYYMMDD
    title           TEXT         NOT NULL,
    abstr           TEXT,                   -- 研报摘要（TuShare直接返回，可直接向量化）
    report_type     VARCHAR(20),            -- 个股研报 / 行业研报
    author          VARCHAR(200),
    stock_name      VARCHAR(100),
    ts_code         VARCHAR(20),
    inst_csname     VARCHAR(100),           -- 机构/券商名称
    ind_name        VARCHAR(100),           -- 行业名称
    url             TEXT,                   -- TuShare 返回的原始 PDF 链接（仅用于下载）
    oss_url         TEXT,                   -- 本机 ingestion 后的阿里云 OSS 全文 URL（Markdown 或摘要纯文本）
    vectorized      BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (trade_date, title, inst_csname)
);

CREATE INDEX IF NOT EXISTS idx_rag_report_date ON alphafrog_rag_research_report(trade_date);
CREATE INDEX IF NOT EXISTS idx_rag_report_ind ON alphafrog_rag_research_report(ind_name);
CREATE INDEX IF NOT EXISTS idx_rag_report_vectorized ON alphafrog_rag_research_report(vectorized) WHERE vectorized = FALSE;
