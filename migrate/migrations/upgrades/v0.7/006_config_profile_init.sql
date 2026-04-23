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
