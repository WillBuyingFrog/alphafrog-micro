-- 初始化 code-refine 配置类型（试点）
INSERT INTO alphafrog_config_type (name, data_id, config_group, service_name, schema_json, description)
VALUES (
    'code-refine',
    'code-refine.json',
    'alphafrog-config',
    'agent-service',
    '{
        "$schema": "http://json-schema.org/draft-07/schema#",
        "type": "object",
        "properties": {
            "maxAttempts": {
                "type": "integer",
                "minimum": 1,
                "maximum": 10,
                "description": "Python 代码执行与纠错的最大尝试次数"
            }
        },
        "required": ["maxAttempts"]
    }',
    'Python 代码纠错最大尝试次数配置'
)
ON CONFLICT (name) DO NOTHING;

-- Agent LLM 主配置
INSERT INTO alphafrog_config_type (name, data_id, config_group, service_name, schema_json, description)
VALUES (
    'agent-llm',
    'agent-llm.json',
    'alphafrog-config',
    'agent-service',
    '{
        "$schema": "http://json-schema.org/draft-07/schema#",
        "type": "object",
        "properties": {
            "defaultEndpoint": {"type": "string"},
            "defaultModel": {"type": "string"},
            "models": {"type": "array", "items": {"type": "string"}},
            "endpoints": {"type": "object"},
            "runtime": {"type": "object"},
            "observability": {"type": "object"},
            "openrouter": {"type": "object"},
            "debug": {"type": "object"},
            "prompts": {"type": "object"}
        },
        "required": ["defaultEndpoint", "defaultModel", "endpoints"]
    }',
    'Agent LLM endpoints/models/runtime/prompts 配置'
)
ON CONFLICT (name) DO NOTHING;

-- ExternalInfo 搜索 LLM 与 WebSearch 多后端配置
INSERT INTO alphafrog_config_type (name, data_id, config_group, service_name, schema_json, description)
VALUES (
    'search-llm',
    'search-llm.json',
    'alphafrog-config',
    'external-info-service',
    '{
        "$schema": "http://json-schema.org/draft-07/schema#",
        "type": "object",
        "properties": {
            "providers": {"type": "object"},
            "features": {
                "type": "object",
                "properties": {
                    "marketNews": {"type": "object"},
                    "webSearch": {"type": "object"}
                },
                "required": ["marketNews"]
            },
            "prompts": {"type": "object"}
        },
        "required": ["providers", "features"]
    }',
    'ExternalInfo 搜索、MarketNews、WebSearch 后端路由配置'
)
ON CONFLICT (name) DO NOTHING;

-- RAG Embedding 配置
INSERT INTO alphafrog_config_type (name, data_id, config_group, service_name, schema_json, description)
VALUES (
    'rag-embedding',
    'rag-embedding.json',
    'alphafrog-config',
    'external-info-service',
    '{
        "$schema": "http://json-schema.org/draft-07/schema#",
        "type": "object",
        "properties": {
            "baseUrl": {"type": "string"},
            "apiKey": {"type": "string"},
            "model": {"type": "string"},
            "dimensions": {"type": "integer", "minimum": 0}
        },
        "required": ["baseUrl", "apiKey", "model"]
    }',
    'RAG 语义检索 Embedding API 配置'
)
ON CONFLICT (name) DO NOTHING;

-- DomesticFetch 抓取任务调度配置
INSERT INTO alphafrog_config_type (name, data_id, config_group, service_name, schema_json, description)
VALUES (
    'fetch-jobs',
    'fetch-jobs.json',
    'alphafrog-config',
    'domestic-fetch-service',
    '{
        "$schema": "http://json-schema.org/draft-07/schema#",
        "type": "object",
        "properties": {
            "scheduledJobs": {"type": "object"},
            "fetch": {
                "type": "object",
                "properties": {
                    "concurrency": {"type": "integer", "minimum": 1},
                    "timeoutSeconds": {"type": "integer", "minimum": 1}
                }
            }
        },
        "required": ["scheduledJobs", "fetch"]
    }',
    'DomesticFetch 定时抓取任务开关、cron 与并发配置'
)
ON CONFLICT (name) DO NOTHING;
