package world.willfrog.agent.workflow;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检测 completed todo 文本与 {@code datasetRefs} 映射表之间的不一致（仅用于 anomaly 事件，不用于注册）。
 */
final class DatasetRefHandoffSupport {

    /**
     * {@link world.willfrog.agent.tool.DatasetWriter} 完整 ID：
     * {@code <prefix>-<tsCode>-<start>-<end>-<uuid>}，其中 prefix 为
     * {@code (runId|unknown)-<assetType>}（见 {@link world.willfrog.agent.tool.MarketDataTools}）。
     * 仅用于 mention 计数，不用于注册。
     */
    private static final Pattern MENTIONED_DATASET_ID = Pattern.compile(
            "(?<![a-f0-9])(?:[a-f0-9]{32}|unknown)-[a-zA-Z0-9_]+-[a-zA-Z0-9._]+-\\d{8}-\\d{8}-[a-f0-9]{8}",
            Pattern.CASE_INSENSITIVE);

    private DatasetRefHandoffSupport() {
    }

    static HandoffMismatch detect(String todoOutput, Map<String, String> datasetRefs) {
        Set<String> mentioned = extractMentionedIds(todoOutput);
        int refsCount = datasetRefs == null ? 0 : datasetRefs.size();
        int mentionedCount = mentioned.size();
        if (mentionedCount <= refsCount) {
            return null;
        }
        List<String> missingSample = new ArrayList<>();
        for (String id : mentioned) {
            if (datasetRefs == null || !datasetRefs.containsKey(id)) {
                missingSample.add(id);
                if (missingSample.size() >= 5) {
                    break;
                }
            }
        }
        List<String> registeredSample = new ArrayList<>();
        if (datasetRefs != null) {
            int i = 0;
            for (String id : datasetRefs.keySet()) {
                registeredSample.add(id);
                i++;
                if (i >= 5) {
                    break;
                }
            }
        }
        return new HandoffMismatch(refsCount, mentionedCount, missingSample, registeredSample);
    }

    static Set<String> extractMentionedIds(String text) {
        Set<String> ids = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return ids;
        }
        Matcher matcher = MENTIONED_DATASET_ID.matcher(text);
        while (matcher.find()) {
            ids.add(matcher.group());
        }
        return ids;
    }

    record HandoffMismatch(
            int datasetRefsCount,
            int mentionedDatasetIdsCount,
            List<String> missingDatasetIdsSample,
            List<String> registeredDatasetIdsSample
    ) {
    }
}
