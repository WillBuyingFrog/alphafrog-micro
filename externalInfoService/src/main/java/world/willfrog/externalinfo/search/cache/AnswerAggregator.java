package world.willfrog.externalinfo.search.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.externalinfo.search.backend.BackendCitation;

import java.util.List;

/**
 * 答案聚合器：当同一热点关键词有多个 backend 的答案时，综合成一个答案。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AnswerAggregator {

    private static final int MAX_AGGREGATED_LENGTH = 2000;

    /**
     * 聚合多个 backend 的答案。
     * 当前使用规则式聚合：单个答案直接返回；多个答案拼接后截取作为保底。
     * TODO P2 后期：接入 LLM 调用，使用构造好的 prompt 进行智能聚合。
     *
     * @param canonicalQuery 规范化查询
     * @param answers        各 backend 的答案列表
     * @return 综合答案
     */
    public String aggregate(String canonicalQuery, List<BackendAnswer> answers) {
        if (answers == null || answers.isEmpty()) {
            return "";
        }

        // 只有一个答案时直接返回
        if (answers.size() == 1) {
            return answers.get(0).answer();
        }

        // 构造聚合 prompt（预留 LLM 调用接口）
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("请根据以下多个来源的回答，综合生成一个简洁、准确的答案。\n");
        promptBuilder.append("问题：").append(canonicalQuery).append("\n\n");
        for (int i = 0; i < answers.size(); i++) {
            BackendAnswer ans = answers.get(i);
            promptBuilder.append("来源 ").append(i + 1).append("（").append(ans.backend()).append("）：\n");
            promptBuilder.append(ans.answer()).append("\n\n");
        }

        // TODO P2 后期：接入 LLM 调用，将 promptBuilder.toString() 作为输入

        // 规则式保底：拼接后截取
        StringBuilder combined = new StringBuilder();
        combined.append("以下是对「").append(canonicalQuery).append("」的多来源综合回答：\n\n");
        for (BackendAnswer ans : answers) {
            combined.append("【").append(ans.backend()).append("】\n");
            combined.append(ans.answer()).append("\n\n");
        }

        String result = combined.toString();
        if (result.length() > MAX_AGGREGATED_LENGTH) {
            result = result.substring(0, MAX_AGGREGATED_LENGTH) + "\n...（内容已截断）";
        }
        return result;
    }

    /**
     * Backend 答案结构
     */
    public record BackendAnswer(
            String backend,
            String answer,
            List<BackendCitation> citations,
            String resultHash,
            String createdAt
    ) {}
}
