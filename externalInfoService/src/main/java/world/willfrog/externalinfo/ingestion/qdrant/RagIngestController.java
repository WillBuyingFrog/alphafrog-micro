package world.willfrog.externalinfo.ingestion.qdrant;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * RAG 向量写入内部端点：接收 frontend 转发的 chunks + embeddings，写入 Qdrant。
 *
 * <p>此端点仅在 Docker 内网（alphafrog-network）可访问，18096 端口未对外暴露。
 * 鉴权由 frontend 层统一处理，此处直接信任来自内网的请求。
 */
@RestController
@RequestMapping("/rag")
@Slf4j
public class RagIngestController {

    private final QdrantService qdrantService;

    public RagIngestController(QdrantService qdrantService) {
        this.qdrantService = qdrantService;
    }

    @PostMapping("/ingest")
    public ResponseEntity<?> ingest(@RequestBody RagIngestRequest request) {
        if (request.getDocId() == null || request.getDocId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "docId is required"));
        }

        int upserted = qdrantService.upsert(request);
        log.info("[RagIngestController] Ingested doc_id={} doc_type={} chunks={} upserted={}",
                request.getDocId(), request.getDocType(),
                request.getChunks() == null ? 0 : request.getChunks().size(),
                upserted);
        return ResponseEntity.ok(Map.of("upserted", upserted, "docId", request.getDocId()));
    }
}
