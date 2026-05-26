package world.willfrog.externalinfo.search;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchAnswerMeta;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchBackendMeta;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchCitation;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchHit;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchRequest;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchResponse;
import world.willfrog.externalinfo.search.backend.BackendCitation;
import world.willfrog.externalinfo.search.backend.BackendHit;
import world.willfrog.externalinfo.search.backend.BackendMeta;
import world.willfrog.externalinfo.search.backend.BackendSearchResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 结果标准化器。
 * 将各 backend 返回的 BackendSearchResult 统一映射为 proto WebSearchResponse。
 */
@Component
@Slf4j
public class ResultNormalizer {

    public WebSearchResponse normalize(BackendSearchResult result, WebSearchRequest request) {
        WebSearchResponse.Builder builder = WebSearchResponse.newBuilder();

        // BackendSearchResult.hits[] -> WebSearchResponse.hits[]
        if (result.hits() != null) {
            for (BackendHit hit : result.hits()) {
                if (hit == null) {
                    continue;
                }
                builder.addHits(mapHit(hit));
            }
        }

        // BackendSearchResult.meta -> WebSearchResponse.backend_meta
        if (result.meta() != null) {
            builder.setBackendMeta(mapMeta(result.meta()));
        }

        // BackendSearchResult.answer（非空时）-> WebSearchResponse.answer
        if (hasText(result.answer())) {
            builder.setAnswer(result.answer().trim());
            builder.setAnswerMeta(WebSearchAnswerMeta.newBuilder()
                    .setAnswerType("backend_native")
                    .build());
        }

        // BackendSearchResult.citations[] -> WebSearchResponse.citations[]
        if (result.citations() != null) {
            for (BackendCitation citation : result.citations()) {
                if (citation == null) {
                    continue;
                }
                builder.addCitations(mapCitation(citation));
            }
        }

        // ok / error
        builder.setOk(result.ok());
        if (!result.ok()) {
            if (hasText(result.errorCode())) {
                builder.setErrorCode(result.errorCode());
            }
            if (hasText(result.errorMessage())) {
                builder.setErrorMessage(result.errorMessage());
            }
        }

        // 回填 P0 预埋字段
        String canonicalQuery = request.getQuery() != null ? request.getQuery().trim() : "";
        builder.setCanonicalQuery(canonicalQuery);

        String resultHash = computeResultHash(result);
        builder.setResultHash(resultHash);

        return builder.build();
    }

    private WebSearchHit mapHit(BackendHit hit) {
        WebSearchHit.Builder builder = WebSearchHit.newBuilder();
        if (hit.title() != null) {
            builder.setTitle(hit.title());
        }
        if (hit.url() != null) {
            builder.setUrl(hit.url());
        }
        if (hit.snippet() != null) {
            builder.setSnippet(hit.snippet());
        }
        if (hit.source() != null) {
            builder.setSource(hit.source());
        }
        if (hit.publishedDate() != null) {
            builder.setPublishedDate(hit.publishedDate());
        }
        if (hit.score() != null) {
            builder.setScore(hit.score());
        }
        return builder.build();
    }

    private WebSearchBackendMeta mapMeta(BackendMeta meta) {
        WebSearchBackendMeta.Builder builder = WebSearchBackendMeta.newBuilder();
        if (meta.backend() != null) {
            builder.setBackend(meta.backend());
        }
        if (meta.modelOrStrength() != null) {
            builder.setModelOrStrength(meta.modelOrStrength());
        }
        if (meta.costEstimateMs() != null) {
            builder.setCostEstimateMs(meta.costEstimateMs());
        }
        if (meta.rawQuerySent() != null) {
            builder.setRawQuerySent(meta.rawQuerySent());
        }
        return builder.build();
    }

    private WebSearchCitation mapCitation(BackendCitation citation) {
        WebSearchCitation.Builder builder = WebSearchCitation.newBuilder();
        builder.setIndex(citation.index());
        if (citation.url() != null) {
            builder.setUrl(citation.url());
        }
        if (citation.title() != null) {
            builder.setTitle(citation.title());
        }
        return builder.build();
    }

    /**
     * 对 hits + answer 做 SHA-256 哈希，生成结果指纹。
     */
    private String computeResultHash(BackendSearchResult result) {
        StringBuilder sb = new StringBuilder();
        if (result.hits() != null) {
            for (BackendHit hit : result.hits()) {
                if (hit != null) {
                    sb.append(nvl(hit.title())).append("|")
                      .append(nvl(hit.url())).append("|")
                      .append(nvl(hit.snippet())).append(";");
                }
            }
        }
        sb.append("|ANSWER|");
        if (hasText(result.answer())) {
            sb.append(result.answer());
        }

        String seed = sb.toString();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(seed.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            log.error("SHA-256 算法不可用", e);
            return "";
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }
}
