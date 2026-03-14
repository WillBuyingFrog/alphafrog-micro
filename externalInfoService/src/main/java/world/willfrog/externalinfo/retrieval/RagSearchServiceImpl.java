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
                        .setDate(getString(pl, "date"))
                        .setChunkText(getString(pl, "chunk_text"))
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
}
