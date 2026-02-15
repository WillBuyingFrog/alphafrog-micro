-- 多轮对话功能：新增 alphafrog_agent_run_message 表
-- 用于存储 Agent Run 的多轮对话消息历史

CREATE TABLE IF NOT EXISTS alphafrog_agent_run_message (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL REFERENCES alphafrog_agent_run(id) ON DELETE CASCADE,
    seq INT NOT NULL, -- 消息序号，同一 run 内递增
    role VARCHAR(20) NOT NULL CHECK (role IN ('user', 'assistant', 'system')),
    content TEXT NOT NULL,
    -- 消息元数据（token 数、模型信息、耗时等）
    meta_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    -- 消息类型：initial(初始问题), follow_up(追问), summary(上下文摘要)
    msg_type VARCHAR(32) NOT NULL DEFAULT 'follow_up' CHECK (msg_type IN ('initial', 'follow_up', 'summary')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (run_id, seq)
);

-- 索引：按 run_id 查询消息历史
CREATE INDEX IF NOT EXISTS idx_agent_run_message_run ON alphafrog_agent_run_message(run_id);

-- 索引：按 run_id + seq 排序查询
CREATE INDEX IF NOT EXISTS idx_agent_run_message_run_seq ON alphafrog_agent_run_message(run_id, seq);

-- 索引：按创建时间排序（用于分页）
CREATE INDEX IF NOT EXISTS idx_agent_run_message_created_at ON alphafrog_agent_run_message(created_at);

COMMENT ON TABLE alphafrog_agent_run_message IS 'Agent Run 多轮对话消息历史表';
COMMENT ON COLUMN alphafrog_agent_run_message.run_id IS '关联的 Run ID';
COMMENT ON COLUMN alphafrog_agent_run_message.seq IS '消息序号，同一 run 内从 1 开始递增';
COMMENT ON COLUMN alphafrog_agent_run_message.role IS '角色：user/assistant/system';
COMMENT ON COLUMN alphafrog_agent_run_message.content IS '消息内容';
COMMENT ON COLUMN alphafrog_agent_run_message.meta_json IS '元数据（token 数、模型名、耗时等）';
COMMENT ON COLUMN alphafrog_agent_run_message.msg_type IS '消息类型：initial/follow_up/summary';
