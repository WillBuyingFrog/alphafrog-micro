package world.willfrog.alphafrogmicro.frontend.service.impl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.frontend.service.RagIngestAuthService;

/**
 * RAG 数据写入二次鉴权服务实现。
 * 当前对比配置文件中硬编码的 token；未来可扩展为从 DB 表读取合法 key 列表。
 */
@Service
public class RagIngestAuthServiceImpl implements RagIngestAuthService {

    @Value("${alphafrog.rag.ingest.admin-token:}")
    private String adminToken;

    @Override
    public boolean isAuthorized(String token) {
        // 未配置 token 则不校验（方便本地开发）
        if (adminToken == null || adminToken.isBlank()) {
            return true;
        }
        return adminToken.equals(token);
    }
}
