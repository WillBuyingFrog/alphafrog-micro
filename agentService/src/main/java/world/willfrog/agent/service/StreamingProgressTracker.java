package world.willfrog.agent.service;

import org.slf4j.Logger;

/**
 * SSE 流式聚合过程中的实时进度追踪器。
 *
 * <p>在流式响应接收过程中实时统计已接收的字符数、chunk 数，
 * 并按配置定期写入 observability 或输出调试日志。</p>
 */
public class StreamingProgressTracker {

    private static final long DEFAULT_UPDATE_INTERVAL_MS = 3000;

    private final Logger log;
    private final String modelName;
    private final String endpointName;
    private final long startTimeMillis;
    private final boolean logProgress;
    private final boolean reportProgress;
    private final long updateIntervalMs;
    private final ProgressReporter progressReporter;

    private int contentCharCount = 0;
    private int reasoningCharCount = 0;
    private int toolCallCharCount = 0;
    private int chunkCount = 0;
    private long lastUpdateTimeMillis = 0;

    public StreamingProgressTracker(Logger log, String modelName, String endpointName) {
        this(log, modelName, endpointName, false, false, DEFAULT_UPDATE_INTERVAL_MS, null);
    }

    public StreamingProgressTracker(Logger log,
                                    String modelName,
                                    String endpointName,
                                    boolean logProgress,
                                    boolean reportProgress,
                                    long updateIntervalMs,
                                    ProgressReporter progressReporter) {
        this.log = log;
        this.modelName = modelName;
        this.endpointName = endpointName;
        this.startTimeMillis = System.currentTimeMillis();
        this.logProgress = logProgress;
        this.reportProgress = reportProgress;
        this.updateIntervalMs = Math.max(1000, updateIntervalMs);
        this.progressReporter = progressReporter;
        this.lastUpdateTimeMillis = this.startTimeMillis;
    }

    /**
     * 每收到一个 SSE chunk 时调用，更新进度计数。
     *
     * @param deltaContent    本次 chunk 的 content delta（可能为 null）
     * @param deltaReasoning  本次 chunk 的 reasoning_content delta（可能为 null）
     */
    public void onChunkReceived(String deltaContent, String deltaReasoning) {
        onChunkReceived(deltaContent, deltaReasoning, 0);
    }

    public void onChunkReceived(String deltaContent, String deltaReasoning, int deltaToolCallChars) {
        chunkCount++;
        if (deltaContent != null) {
            contentCharCount += deltaContent.length();
        }
        if (deltaReasoning != null) {
            reasoningCharCount += deltaReasoning.length();
        }
        if (deltaToolCallChars > 0) {
            toolCallCharCount += deltaToolCallChars;
        }

        long now = System.currentTimeMillis();
        if (now - lastUpdateTimeMillis >= updateIntervalMs) {
            lastUpdateTimeMillis = now;
            long elapsedMs = now - startTimeMillis;
            StreamingProgressSnapshot snapshot = buildSnapshot(elapsedMs);
            emitProgress(snapshot, false);
        }
    }

    /**
     * 流式响应结束时调用，输出最终统计日志。
     *
     * @param durationMs 总耗时（毫秒）
     */
    public StreamingProgressSnapshot onStreamComplete(long durationMs) {
        StreamingProgressSnapshot snapshot = buildSnapshot(durationMs);
        emitProgress(snapshot, true);
        return snapshot;
    }

    /**
     * 获取当前进度快照。
     */
    public StreamingProgressSnapshot getSnapshot() {
        long durationMs = System.currentTimeMillis() - startTimeMillis;
        return buildSnapshot(durationMs);
    }

    private StreamingProgressSnapshot buildSnapshot(long durationMs) {
        int totalCharCount = contentCharCount + reasoningCharCount + toolCallCharCount;
        double charsPerSecond = durationMs > 0 ? (double) totalCharCount * 1000.0 / durationMs : 0.0;
        return new StreamingProgressSnapshot(
                contentCharCount,
                reasoningCharCount,
                toolCallCharCount,
                totalCharCount,
                chunkCount,
                durationMs,
                charsPerSecond
        );
    }

    private void emitProgress(StreamingProgressSnapshot snapshot, boolean completed) {
        if (logProgress && log.isInfoEnabled()) {
            log.info("SSE进度 endpoint={} model={} completed={} chunks={} contentChars={} reasoningChars={} toolCallChars={} totalChars={} durationMs={} charsPerSecond={}",
                    endpointName,
                    modelName,
                    completed,
                    snapshot.chunkCount(),
                    snapshot.contentCharCount(),
                    snapshot.reasoningCharCount(),
                    snapshot.toolCallCharCount(),
                    snapshot.totalCharCount(),
                    snapshot.durationMs(),
                    String.format("%.1f", snapshot.charsPerSecond()));
        }
        if (reportProgress && progressReporter != null) {
            progressReporter.report(snapshot, completed);
        }
    }

    /**
     * 流式进度快照，用于保存到 AgentContext 和 observability。
     */
    public record StreamingProgressSnapshot(
            int contentCharCount,
            int reasoningCharCount,
            int toolCallCharCount,
            int totalCharCount,
            int chunkCount,
            long durationMs,
            double charsPerSecond
    ) {
    }

    @FunctionalInterface
    public interface ProgressReporter {
        void report(StreamingProgressSnapshot snapshot, boolean completed);
    }
}
