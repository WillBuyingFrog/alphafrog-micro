package world.willfrog.agent.service;

import org.slf4j.Logger;

/**
 * SSE 流式聚合过程中的实时进度追踪器。
 *
 * <p>在流式响应接收过程中实时统计已接收的字符数、chunk 数，
 * 并定期输出日志以便观测当前接收进度。</p>
 */
public class StreamingProgressTracker {

    private static final long LOG_INTERVAL_MS = 1000;

    private final Logger log;
    private final String modelName;
    private final String endpointName;
    private final long startTimeMillis;

    private int contentCharCount = 0;
    private int reasoningCharCount = 0;
    private int chunkCount = 0;
    private long lastLogTimeMillis = 0;

    public StreamingProgressTracker(Logger log, String modelName, String endpointName) {
        this.log = log;
        this.modelName = modelName;
        this.endpointName = endpointName;
        this.startTimeMillis = System.currentTimeMillis();
        this.lastLogTimeMillis = this.startTimeMillis;
    }

    /**
     * 每收到一个 SSE chunk 时调用，更新进度计数。
     *
     * @param deltaContent    本次 chunk 的 content delta（可能为 null）
     * @param deltaReasoning  本次 chunk 的 reasoning_content delta（可能为 null）
     */
    public void onChunkReceived(String deltaContent, String deltaReasoning) {
        chunkCount++;
        if (deltaContent != null) {
            contentCharCount += deltaContent.length();
        }
        if (deltaReasoning != null) {
            reasoningCharCount += deltaReasoning.length();
        }

        long now = System.currentTimeMillis();
        if (now - lastLogTimeMillis >= LOG_INTERVAL_MS) {
            lastLogTimeMillis = now;
            long elapsedMs = now - startTimeMillis;
            if (log.isInfoEnabled()) {
                log.info("SSE进度 endpoint={} model={} chunks={} contentChars={} reasoningChars={} elapsedMs={}",
                        endpointName, modelName, chunkCount, contentCharCount, reasoningCharCount, elapsedMs);
            }
        }
    }

    /**
     * 流式响应结束时调用，输出最终统计日志。
     *
     * @param durationMs 总耗时（毫秒）
     */
    public void onStreamComplete(long durationMs) {
        double charsPerSecond = durationMs > 0
                ? (double) (contentCharCount + reasoningCharCount) * 1000.0 / durationMs
                : 0.0;
        if (log.isInfoEnabled()) {
            log.info("SSE完成 endpoint={} model={} chunks={} contentChars={} reasoningChars={} durationMs={} charsPerSecond={}",
                    endpointName, modelName, chunkCount, contentCharCount, reasoningCharCount, durationMs,
                    String.format("%.1f", charsPerSecond));
        }
    }

    /**
     * 获取当前进度快照。
     */
    public StreamingProgressSnapshot getSnapshot() {
        long durationMs = System.currentTimeMillis() - startTimeMillis;
        double charsPerSecond = durationMs > 0
                ? (double) (contentCharCount + reasoningCharCount) * 1000.0 / durationMs
                : 0.0;
        return new StreamingProgressSnapshot(contentCharCount, reasoningCharCount, chunkCount, durationMs, charsPerSecond);
    }

    /**
     * 流式进度快照，用于保存到 AgentContext 和 observability。
     */
    public record StreamingProgressSnapshot(
            int contentCharCount,
            int reasoningCharCount,
            int chunkCount,
            long durationMs,
            double charsPerSecond
    ) {
    }
}
