package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import world.willfrog.agent.config.AgentLlmProperties;
import world.willfrog.agent.model.openrouter.GenerationResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

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

    /**
     * 异步查询 Generation API 获取费用信息并补充到观测数据中。
     *
     * @param runId        Run ID
     * @param traceId      LLM Trace ID
     * @param generationId OpenRouter generation ID（如 gen-xxx）
     * @param apiKey       OpenRouter API Key
     * @param baseUrl      OpenRouter base URL
     */
    @Async
    public void enrichCostInfoAsync(String runId, String traceId, String generationId,
                                    String apiKey, String baseUrl) {
        if (!isCostEnrichmentEnabled()) {
            return;
        }
        if (generationId == null || generationId.isBlank()) {
            return;
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

            int timeoutMs = getTimeoutMs();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs > 0 ? timeoutMs : 5000))
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
                    observabilityService.enrichLlmCallSpending(
                            runId,
                            traceId,
                            data.getTotalCost(),
                            data.getUpstreamInferenceCost(),
                            data.getCacheDiscount(),
                            data.getIsByok()
                    );
                }
            } else {
                log.debug("OpenRouter Generation API returned status {}: {}",
                        response.statusCode(), response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while fetching cost info for generation {}", generationId);
        } catch (Exception e) {
            log.warn("Failed to get cost info for generation {}: {}", generationId, e.getMessage());
        }
    }
}
