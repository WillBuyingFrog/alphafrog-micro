-- 补齐运行时动态配置类型。
--
-- 背景：
-- 早期测试环境可能已经执行过只包含 code-refine 的 006_config_profile_init.sql。
-- 由于迁移工具按目标版本记录进度，这类环境会显示已经到达 v0.7，但缺少后续补充的
-- agent/search/rag/fetch 配置类型。这里用独立 v0.7.1 幂等迁移进行修复。

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
ON CONFLICT (name) DO UPDATE SET
    data_id = EXCLUDED.data_id,
    config_group = EXCLUDED.config_group,
    service_name = EXCLUDED.service_name,
    schema_json = EXCLUDED.schema_json,
    description = EXCLUDED.description;

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
ON CONFLICT (name) DO UPDATE SET
    data_id = EXCLUDED.data_id,
    config_group = EXCLUDED.config_group,
    service_name = EXCLUDED.service_name,
    schema_json = EXCLUDED.schema_json,
    description = EXCLUDED.description;

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
ON CONFLICT (name) DO UPDATE SET
    data_id = EXCLUDED.data_id,
    config_group = EXCLUDED.config_group,
    service_name = EXCLUDED.service_name,
    schema_json = EXCLUDED.schema_json,
    description = EXCLUDED.description;

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
ON CONFLICT (name) DO UPDATE SET
    data_id = EXCLUDED.data_id,
    config_group = EXCLUDED.config_group,
    service_name = EXCLUDED.service_name,
    schema_json = EXCLUDED.schema_json,
    description = EXCLUDED.description;
