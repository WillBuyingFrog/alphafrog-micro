package world.willfrog.alphafrogmicro.frontend.service;

/**
 * RAG 数据写入二次鉴权服务。
 * 当前实现对比配置文件 token；未来可扩展为从 DB 表读取合法 key 列表。
 */
public interface RagIngestAuthService {

    /**
     * 校验 ingest_token 是否合法。
     *
     * @param token 客户端传入的 token
     * @return true 表示鉴权通过
     */
    boolean isAuthorized(String token);
}
