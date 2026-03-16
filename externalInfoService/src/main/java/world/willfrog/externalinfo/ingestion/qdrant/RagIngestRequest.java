package world.willfrog.externalinfo.ingestion.qdrant;

import java.util.List;
import java.util.Map;

/**
 * /rag/ingest HTTP 请求体：携带文档分块、embedding 向量和元数据。
 */
public class RagIngestRequest {

    private String docId;
    private String docType;
    private List<ChunkItem> chunks;

    public String getDocId() { return docId; }
    public void setDocId(String docId) { this.docId = docId; }

    public String getDocType() { return docType; }
    public void setDocType(String docType) { this.docType = docType; }

    public List<ChunkItem> getChunks() { return chunks; }
    public void setChunks(List<ChunkItem> chunks) { this.chunks = chunks; }

    public static class ChunkItem {
        private String text;
        private List<Double> embedding;
        private Map<String, Object> metadata;

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }

        public List<Double> getEmbedding() { return embedding; }
        public void setEmbedding(List<Double> embedding) { this.embedding = embedding; }

        public Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    }
}
