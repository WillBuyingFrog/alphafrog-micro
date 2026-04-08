package world.willfrog.externalinfo.retrieval;

import lombok.Data;

/**
 * RAG 语义检索 Embedding API 本地配置（热加载 JSON 的映射对象）。
 *
 * <p>对应文件：externalInfoService/config/rag-embedding.local.json</p>
 * <p>本地 JSON 中的非空值会覆盖 application.yml / 环境变量中的默认值，实现运行时热更新。</p>
 */
@Data
public class RagEmbeddingProperties {

    /**
     * Embedding API 基础地址（OpenAI 兼容格式），如 https://api.openai.com/v1。
     * 为空时使用 application.yml 中的 AF_EMBEDDING_BASE_URL。
     */
    private String baseUrl = "";

    /**
     * API Key。为空时使用 application.yml 中的 AF_EMBEDDING_API_KEY。
     */
    private String apiKey = "";

    /**
     * 模型名称，如 text-embedding-3-small。
     * 为空时使用 application.yml 中的 AF_EMBEDDING_MODEL。
     * 必须与 ingestion 脚本使用的模型一致。
     */
    private String model = "";

    /**
     * 向量维度，如 1024。
     * 为 0 时使用 application.yml 中的 AF_EMBEDDING_DIMENSIONS（默认 1024）。
     * 必须与 ingestion 脚本使用的维度一致。
     */
    private int dimensions = 0;
}
