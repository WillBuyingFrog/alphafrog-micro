package world.willfrog.externalinfo.ingestion.qdrant;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 封装对 Qdrant REST API 的调用：建集合、批量写入向量点。
 */
@Service
@Slf4j
public class QdrantService {

    private final String baseUrl;
    private final String collectionName;
    private final int embeddingDim;
    private final ObjectMapper objectMapper;

    public QdrantService(
            @Value("${alphafrog.rag.ingest.qdrant-host:shared-qdrant}") String qdrantHost,
            @Value("${alphafrog.rag.ingest.qdrant-http-port:6333}") int qdrantPort,
            @Value("${alphafrog.rag.ingest.qdrant-collection:alphafrog_rag}") String collectionName,
            @Value("${alphafrog.rag.ingest.embedding-dim:1024}") int embeddingDim,
            ObjectMapper objectMapper) {
        this.baseUrl = "http://" + qdrantHost + ":" + qdrantPort;
        this.collectionName = collectionName;
        this.embeddingDim = embeddingDim;
        this.objectMapper = objectMapper;
    }

    /**
     * 服务启动后确保 Qdrant collection 存在（不存在则创建）。
     */
    @PostConstruct
    public void ensureCollection() {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            // 先检查 collection 是否存在
            HttpGet getReq = new HttpGet(baseUrl + "/collections/" + collectionName);
            try (ClassicHttpResponse resp = client.execute(getReq)) {
                int status = resp.getCode();
                EntityUtils.consume(resp.getEntity());
                if (status == 200) {
                    log.info("[QdrantService] Collection '{}' already exists", collectionName);
                    return;
                }
            }

            // 创建 collection
            Map<String, Object> body = Map.of(
                    "vectors", Map.of(
                            "size", embeddingDim,
                            "distance", "Cosine"
                    )
            );
            HttpPut putReq = new HttpPut(baseUrl + "/collections/" + collectionName);
            putReq.setEntity(new StringEntity(objectMapper.writeValueAsString(body), ContentType.APPLICATION_JSON));

            try (ClassicHttpResponse resp = client.execute(putReq)) {
                int status = resp.getCode();
                String respBody = EntityUtils.toString(resp.getEntity());
                if (status == 200) {
                    log.info("[QdrantService] Created collection '{}' dim={}", collectionName, embeddingDim);
                } else {
                    log.warn("[QdrantService] Failed to create collection '{}': status={} body={}",
                            collectionName, status, respBody);
                }
            }
        } catch (Exception e) {
            log.warn("[QdrantService] Could not ensure collection '{}', Qdrant may not be available: {}",
                    collectionName, e.getMessage());
        }
    }

    /**
     * 将 ingest 请求中的所有 chunk 写入 Qdrant，返回成功写入的点数。
     */
    public int upsert(RagIngestRequest request) {
        if (request.getChunks() == null || request.getChunks().isEmpty()) {
            return 0;
        }

        List<Map<String, Object>> points = new ArrayList<>();
        for (int i = 0; i < request.getChunks().size(); i++) {
            RagIngestRequest.ChunkItem chunk = request.getChunks().get(i);
            if (chunk.getEmbedding() == null || chunk.getEmbedding().isEmpty()) {
                continue;
            }

            // 用 doc_id + 序号生成确定性 UUID
            String pointId = UUID.nameUUIDFromBytes(
                    (request.getDocId() + "_" + i).getBytes()
            ).toString();

            Map<String, Object> payload = new HashMap<>();
            if (chunk.getMetadata() != null) {
                payload.putAll(chunk.getMetadata());
            }
            payload.put("text", chunk.getText());
            payload.put("doc_type", request.getDocType());
            payload.put("chunk_index", i);

            points.add(Map.of(
                    "id", pointId,
                    "vector", chunk.getEmbedding(),
                    "payload", payload
            ));
        }

        if (points.isEmpty()) {
            return 0;
        }

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            Map<String, Object> body = Map.of("points", points);
            HttpPut putReq = new HttpPut(
                    baseUrl + "/collections/" + collectionName + "/points?wait=true"
            );
            putReq.setEntity(new StringEntity(objectMapper.writeValueAsString(body), ContentType.APPLICATION_JSON));

            try (ClassicHttpResponse resp = client.execute(putReq)) {
                int status = resp.getCode();
                String respBody = EntityUtils.toString(resp.getEntity());
                if (status == 200) {
                    log.debug("[QdrantService] Upserted {} points for doc_id={}", points.size(), request.getDocId());
                    return points.size();
                } else {
                    log.warn("[QdrantService] Upsert failed for doc_id={}: status={} body={}",
                            request.getDocId(), status, respBody);
                    return 0;
                }
            }
        } catch (Exception e) {
            log.error("[QdrantService] Upsert error for doc_id={}: {}", request.getDocId(), e.getMessage(), e);
            return 0;
        }
    }

    public String getCollectionName() {
        return collectionName;
    }

    public String getBaseUrl() {
        return baseUrl;
    }
}
