package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.model.openrouter.GenerationResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 异步查询 OpenRouter Generation API 获取 Spending 信息。
 *
 * <p>在 LLM 调用完成后，可选地异步查询 OpenRouter 的 Generation API
 * 获取真实费用信息（total_cost, upstream_inference_cost, cache_discount 等），
 * 并通过 {@link AgentObservabilityService#enrichLlmCallSpending} 补充到对应的 LLM Trace 中。</p>
 *
 * <p><b>启用条件：</b></p>
 * <ul>
 *   <li>{@code agent.observability.openrouter.cost-enrichment.enabled=true}</li>
 * </ul>
 *
 * @see AgentObservabilityService#enrichLlmCallSpending
 * @see GenerationResponse
 */
@Service
@Slf4j
public class OpenRouterCostService {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final AgentObservabilityService observabilityService;
    private final ObjectMapper objectMapper;
    private final AgentLlmLocalConfigLoader localConfigLoader;

    // 默认配置（从环境变量或 application.yml 读取）
    @Value("${agent.observability.openrouter.cost-enrichment.enabled:false}")
    private boolean defaultCostEnrichmentEnabled;

    @Value("${agent.observability.openrouter.cost-enrichment.timeout-ms:5000}")
    private int defaultTimeoutMs;

    @Value("${agent.observability.openrouter.cost-enrichment.max-attempts:3}")
    private int defaultMaxAttempts;

    @Value("${agent.observability.openrouter.cost-enrichment.retry-delay-ms:1000}")
    private int defaultRetryDelayMs;

    public OpenRouterCostService(AgentObservabilityService observabilityService, 
                                  ObjectMapper objectMapper,
                                  AgentLlmLocalConfigLoader localConfigLoader) {
        this.observabilityService = observabilityService;
        this.objectMapper = objectMapper;
        this.localConfigLoader = localConfigLoader;
    }

    /**
     * 获取当前启用的 spending 记录开关。
     * 优先从 agent-llm.local.json 读取，如果没有配置则使用默认值（环境变量/application.yml）。
     */
    private boolean isCostEnrichmentEnabled() {
        return localConfigLoader.current()
                .map(cfg -> cfg.getObservability())
                .map(obs -> obs.getOpenrouter())
                .map(router -> router.getCostEnrichment())
                .map(ce -> ce.getEnabled())
                .orElse(defaultCostEnrichmentEnabled);
    }

    /**
     * 获取当前超时时间（毫秒）。
     * 优先从 agent-llm.local.json 读取，如果没有配置则使用默认值。
     */
    private int getTimeoutMs() {
        return localConfigLoader.current()
                .map(cfg -> cfg.getObservability())
                .map(obs -> obs.getOpenrouter())
                .map(router -> router.getCostEnrichment())
                .map(ce -> ce.getTimeoutMs())
                .orElse(defaultTimeoutMs);
    }

    private int getMaxAttempts() {
        return positiveOrDefault(localConfigLoader.current()
                .map(cfg -> cfg.getObservability())
                .map(obs -> obs.getOpenrouter())
                .map(router -> router.getCostEnrichment())
                .map(ce -> ce.getMaxAttempts())
                .orElse(defaultMaxAttempts), 3);
    }

    private int getRetryDelayMs() {
        return positiveOrDefault(localConfigLoader.current()
                .map(cfg -> cfg.getObservability())
                .map(obs -> obs.getOpenrouter())
                .map(router -> router.getCostEnrichment())
                .map(ce -> ce.getRetryDelayMs())
                .orElse(defaultRetryDelayMs), 1000);
    }

    private int positiveOrDefault(Integer value, int defaultValue) {
        return value != null && value > 0 ? value : defaultValue;
    }

    public int enrichMissingCostInfo(String runId, String apiKey, String baseUrl) {
        if (runId == null || runId.isBlank() || !isCostEnrichmentEnabled()) {
            return 0;
        }
        AgentObservabilityService.ObservabilityState state = observabilityService.forceFlush(runId);
        if (state == null || state.getDiagnostics() == null || state.getDiagnostics().getLlmTraces() == null) {
            return 0;
        }

        Set<String> seenGenerationIds = new LinkedHashSet<>();
        int enriched = 0;
        for (AgentObservabilityService.LlmTrace trace : state.getDiagnostics().getLlmTraces()) {
            if (trace == null || trace.getActualCost() != null) {
                continue;
            }
            String generationId = generationIdFromTrace(trace);
            if (generationId == null || generationId.isBlank() || !seenGenerationIds.add(generationId)) {
                continue;
            }
            if (enrichCostInfo(runId, trace.getTraceId(), generationId, apiKey, baseUrl)) {
                enriched++;
            }
        }
        return enriched;
    }

    private String generationIdFromTrace(AgentObservabilityService.LlmTrace trace) {
        if (trace == null) {
            return null;
        }
        if (trace.getGenerationId() != null && !trace.getGenerationId().isBlank()) {
            return trace.getGenerationId();
        }
        AgentObservabilityService.RawHttpTrace response = trace.getHttpResponse();
        if (response == null || response.getBody() == null || response.getBody().isBlank()) {
            return null;
        }
        try {
            var root = objectMapper.readTree(response.getBody());
            var id = root.get("id");
            return id != null && id.isTextual() && !id.asText().isBlank() ? id.asText() : null;
        } catch (Exception e) {
            log.debug("Failed to extract generation id from trace {}: {}", trace.getTraceId(), e.getMessage());
            return null;
        }
    }

    /**
     * 异步查询 Generation API 获取费用信息并补充到观测数据中。
     *
     * @param runId        Run ID
     * @param traceId      LLM Trace ID
     * @param generationId OpenRouter generation ID（如 gen-xxx）
     * @param apiKey       OpenRouter API Key
     * @param baseUrl      OpenRouter base URL
     */
    public boolean enrichCostInfo(String runId, String traceId, String generationId,
                               String apiKey, String baseUrl) {
        if (!isCostEnrichmentEnabled()) {
            return false;
        }
        if (generationId == null || generationId.isBlank()) {
            return false;
        }

        try {
            String normalizedBase = baseUrl != null ? baseUrl.trim() : "https://openrouter.ai/api/v1";
            if (normalizedBase.endsWith("/")) {
                normalizedBase = normalizedBase.substring(0, normalizedBase.length() - 1);
            }
            // Strip known sub-paths to get the base domain for the Generation API
            // e.g. "https://openrouter.ai/api/v1" -> "https://openrouter.ai"
            if (normalizedBase.endsWith("/api/v1")) {
                normalizedBase = normalizedBase.substring(0, normalizedBase.length() - "/api/v1".length());
            } else if (normalizedBase.endsWith("/v1")) {
                normalizedBase = normalizedBase.substring(0, normalizedBase.length() - "/v1".length());
            }
            String url = normalizedBase + "/api/v1/generation?id=" + generationId;

            int timeoutMs = positiveOrDefault(getTimeoutMs(), 5000);
            int maxAttempts = getMaxAttempts();
            int retryDelayMs = getRetryDelayMs();

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofMillis(timeoutMs))
                        .header("Authorization", "Bearer " + (apiKey != null ? apiKey : ""))
                        .header("Accept", "application/json")
                        .GET()
                        .build();

                HttpResponse<String> response = HTTP_CLIENT.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                if (response.statusCode() >= 200 && response.statusCode() < 300
                        && response.body() != null && !response.body().isBlank()) {
                    GenerationResponse genResponse = objectMapper.readValue(response.body(), GenerationResponse.class);
                    if (genResponse != null && genResponse.getData() != null) {
                        GenerationResponse.GenerationData data = genResponse.getData();
                        if (data.getTotalCost() != null || data.getUpstreamInferenceCost() != null
                                || data.getCacheDiscount() != null) {
                            observabilityService.enrichLlmCallSpending(
                                    runId,
                                    traceId,
                                    data.getTotalCost(),
                                    data.getUpstreamInferenceCost(),
                                    data.getCacheDiscount(),
                                    data.getIsByok()
                            );
                            return true;
                        }
                    }
                    log.debug("OpenRouter Generation API returned no cost data for generation {} on attempt {}/{}: {}",
                            generationId, attempt, maxAttempts, response.body());
                } else {
                    log.warn("OpenRouter Generation API returned status {} for generation {} on attempt {}/{}: {}",
                            response.statusCode(), generationId, attempt, maxAttempts, response.body());
                }

                if (attempt < maxAttempts) {
                    Thread.sleep(retryDelayMs);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while fetching cost info for generation {}", generationId);
        } catch (Exception e) {
            log.warn("Failed to get cost info for generation {}: {}", generationId, e.getMessage());
        }
        return false;
    }
}
