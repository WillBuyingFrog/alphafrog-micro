package world.willfrog.externalinfo.retrieval;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import io.qdrant.client.grpc.Points.WithPayloadSelector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.externalinfo.idl.RagSearchRequest;
import world.willfrog.alphafrogmicro.externalinfo.idl.RagSearchResponse;
import world.willfrog.alphafrogmicro.externalinfo.idl.RagSearchResultItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.qdrant.client.ConditionFactory.matchKeyword;

@Service
@Slf4j
public class RagSearchServiceImpl {

    private final QdrantClient qdrantClient;
    private final EmbeddingApiClient embeddingApiClient;

    @org.springframework.beans.factory.annotation.Value("${alphafrog.rag.qdrant.collection:alphafrog_financial_docs}")
    private String collectionName;

    private static final int DEFAULT_TOP_K = 5;

    @Autowired
    public RagSearchServiceImpl(QdrantClient qdrantClient, EmbeddingApiClient embeddingApiClient) {
        this.qdrantClient = qdrantClient;
        this.embeddingApiClient = embeddingApiClient;
    }

    public RagSearchResponse ragSearch(RagSearchRequest request) {
        try {
            int topK = request.getTopK() > 0 ? request.getTopK() : DEFAULT_TOP_K;
            List<Float> queryVec = embeddingApiClient.embed(request.getQueryText());

            SearchPoints.Builder builder = SearchPoints.newBuilder()
                    .setCollectionName(collectionName)
                    .addAllVector(queryVec)
                    .setLimit(topK)
                    .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build());

            Filter.Builder filterBuilder = Filter.newBuilder();
            boolean hasFilter = false;
            if (!request.getDocType().isBlank()) {
                filterBuilder.addMust(matchKeyword("doc_type", request.getDocType()));
                hasFilter = true;
            }
            if (!request.getTsCode().isBlank()) {
                filterBuilder.addMust(matchKeyword("ts_code", request.getTsCode()));
                hasFilter = true;
            }
            if (!request.getIndName().isBlank()) {
                filterBuilder.addMust(matchKeyword("ind_name", request.getIndName()));
                hasFilter = true;
            }
            if (hasFilter) {
                builder.setFilter(filterBuilder.build());
            }

            List<ScoredPoint> points = qdrantClient.searchAsync(builder.build()).get();

            List<RagSearchResultItem> items = new ArrayList<>();
            for (ScoredPoint p : points) {
                Map<String, Value> pl = p.getPayloadMap();
                items.add(RagSearchResultItem.newBuilder()
                        .setScore(p.getScore())
                        .setDocType(getString(pl, "doc_type"))
                        .setTsCode(getString(pl, "ts_code"))
                        .setIndName(getString(pl, "ind_name"))
                        .setTitle(getString(pl, "title"))
                        // ingestion 侧用 ann_date（公告）或 trade_date（研报），且以 BIGINT ms 存储
                        .setDate(getDate(pl))
                        // ingestion 侧 chunk 文本存于 "text" 字段
                        .setChunkText(getString(pl, "text"))
                        .setOssUrl(getString(pl, "oss_url"))
                        .build());
            }
            return RagSearchResponse.newBuilder()
                    .addAllItems(items)
                    .setTotal(items.size())
                    .build();
        } catch (Exception e) {
            log.error("RagSearch failed", e);
            return RagSearchResponse.newBuilder().setTotal(0).build();
        }
    }

    private String getString(Map<String, Value> payload, String key) {
        Value v = payload.get(key);
        return v != null ? v.getStringValue() : "";
    }

    /**
     * 从 payload 中读取日期值。
     * ingestion 侧：公告存 "ann_date"、研报存 "trade_date"，均为 BIGINT ms 时间戳。
     * 优先取 ann_date，其次 trade_date，两者均为整数时直接返回数字字符串。
     */
    private String getDate(Map<String, Value> payload) {
        for (String key : new String[]{"ann_date", "trade_date"}) {
            Value v = payload.get(key);
            if (v == null) {
                continue;
            }
            // 字符串类型（兼容未来可能改为字符串存储的情况）
            String s = v.getStringValue();
            if (!s.isBlank()) {
                return s;
            }
            // 整数类型（当前 ingestion 以 BIGINT ms 写入）
            if (v.getIntegerValue() != 0) {
                return String.valueOf(v.getIntegerValue());
            }
        }
        return "";
    }
}
