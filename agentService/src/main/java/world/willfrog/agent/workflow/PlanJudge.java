package world.willfrog.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.service.AgentPromptService;
import world.willfrog.agent.platform.service.ReactConversationContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plan Judge：当某个 Todo 执行失败时，调用 LLM 对失败原因作出诊断与决策。
 *
 * <h3>角色与位置</h3>
 * 本组件是 {@link LinearWorkflowExecutor} 和 {@link DagWorkflowExecutor} 失败恢复链路上的关键决策节点。
 * 当某个 Todo 在 ReAct 循环中失败后，上层执行器会调用本类的 {@code judge}，
 * 让 LLM 根据失败摘要 + 失败输出预览 + 当前 Plan 状态 + 已完成 Todo 上下文，输出一个
 * {@link JudgeDecision} 枚举值，决定后续行为：
 * <ul>
 *   <li>{@link JudgeDecision#RETRY} / {@link JudgeDecision#CONTINUE_WITH_RECOVERY_PARAMS}
 *       — 原地重试当前 Todo（不修改 Plan）。</li>
 *   <li>{@link JudgeDecision#PATCH_PLAN} — 让 {@link PatchPlanner} 生成 PlanPatch，修改 Plan 结构后重新执行。</li>
 *   <li>{@link JudgeDecision#FALLBACK_TO_LINEAR} — 仅 DAG 执行器使用，将剩余未完成的 Todo 改为线性串行执行。</li>
 *   <li>{@link JudgeDecision#FAIL} / {@link JudgeDecision#ABORT} — 立即终止 Run，标记为失败。</li>
 * </ul>
 *
 * <h3>两种调用模式</h3>
 * <ol>
 *   <li><b>ReAct 累积模式</b>（{@link #judge(ReactConversationContext, TodoExecutionRecord, TodoPlan, Map, ChatModel)}）：
 *       与 Planning 阶段共享同一个 {@link ReactConversationContext}，复用 System Prompt 前缀
 *       以最大化 LLM provider 的 KV 缓存命中率。</li>
 *   <li><b>独立调用模式</b>（{@link #judge(TodoExecutionRecord, TodoPlan, Map, String, ChatModel)}）：
 *       使用 PlanJudge 专属的 System Prompt 独立调用 LLM，向后兼容旧的调用方。</li>
 * </ol>
 *
 * <h3>LLM 输出解析</h3>
 * LLM 输出可能是结构化 JSON（{@code {"decision":"PATCH_PLAN", ...}}），也可能是纯文本提示。
 * {@link #parseDecision} 会先尝试解析 JSON，失败再退化为字符串关键字匹配，
 * 最坏情况返回 {@link JudgeDecision#FAIL}（保守策略，避免误回滚或误重试）。
 *
 * @see PatchPlanner
 * @see PlanPatcher
 * @see JudgeDecision
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlanJudge {

    /** 提示词服务，提供 PlanJudge 阶段的 System Prompt 和 stage instruction */
    private final AgentPromptService promptService;
    /** JSON 序列化/反序列化器，用于构建 LLM 输入 payload 和解析输出 */
    private final ObjectMapper objectMapper;

    /**
     * ReAct 累积模式：通过 ReactConversationContext 进行判断，共享 System Prompt 前缀，
     * 最大化 LLM provider 的 KV 缓存命中率。
     *
     * <p>使用场景：当 Planning 阶段（{@link TodoPlanner}）和后续判断阶段在同一会话中，
     * 复用累积的 System Prompt 可让 provider 的前缀缓存命中率最大化，从而降低 token 费用和首字延迟。</p>
     *
     * <p>流程：</p>
     * <ol>
     *   <li>读取 PlanJudge 阶段的 stage instruction（来自 prompt 配置），为空时直接返回 FAIL。</li>
     *   <li>构建 payload（失败记录预览 + 当前 Plan 大小 + 已完成 Todo 上下文 keys）。</li>
     *   <li>将 dynamic prefix + stage instruction + payload JSON 作为新一轮 UserMessage 注入上下文。</li>
     *   <li>调用 LLM，将 AssistantMessage 追加到上下文，便于后续多轮 ReAct 持续累积。</li>
     *   <li>解析 LLM 输出为 {@link JudgeDecision}。</li>
     * </ol>
     *
     * @param reactCtx    与 Planning 共享的 ReAct 上下文，调用本方法会向其追加一组 user/assistant 消息
     * @param record      失败 Todo 的执行记录（来自 ReactTodoExecutor）
     * @param currentPlan 当前 Plan（决定 plan 大小、用于 LLM 评估是否可 patch）
     * @param context     已完成 Todo 的执行上下文映射（todoId → record），供 LLM 了解前序任务状态
     * @param model       用于决策的 ChatModel
     * @return 判定结果；LLM 调用异常时退回 FAIL
     */
    public JudgeDecision judge(ReactConversationContext reactCtx,
                               TodoExecutionRecord record,
                               TodoPlan currentPlan,
                               Map<String, TodoExecutionRecord> context,
                               ChatModel model) {
        String stageInstruction = promptService.planJudgeStageInstruction();
        if (stageInstruction == null || stageInstruction.isBlank()) {
            log.debug("PlanJudge stage instruction is empty, defaulting to FAIL");
            return JudgeDecision.FAIL;
        }

        // 构建结构化 payload；ReAct 累积模式下 user_goal 已包含在共享的对话上下文中，故传 null 避免重复
        Map<String, Object> payload = buildJudgePayload(record, currentPlan, context, null);
        String userMessage = safeWrite(payload);

        // 组合提示：动态上下文前缀 + 阶段指令 + payload JSON
        String userContent = promptService.dynamicContextPrefix() + "\n"
                + stageInstruction + "\n" + userMessage;
        reactCtx.addUserMessage(userContent);

        try {
            ChatResponse response = model.chat(reactCtx.getMessages());
            String text = response.aiMessage() == null ? "" : nvl(response.aiMessage().text());
            // 将 LLM 回复纳入累积上下文，供后续轮次 ReAct 继续推理
            reactCtx.addAssistantMessage(text);
            return parseDecision(text);
        } catch (Exception e) {
            log.warn("PlanJudge LLM call (ReAct) failed: {}", e.getMessage());
            // 保守策略：LLM 不可用时直接 FAIL，避免误判进入无效重试
            return JudgeDecision.FAIL;
        }
    }

    /**
     * 独立调用模式（向后兼容）：使用独立的 System Prompt 调用 LLM。
     *
     * <p>使用场景：调用方未持有 {@link ReactConversationContext}（如直接从执行器单独调起），
     * 此时用 PlanJudge 专属 System Prompt + 单条 UserMessage 构造一次最小化对话。</p>
     *
     * <p>由于不共享前缀缓存，会比 ReAct 累积模式略多消耗 token；
     * 但在执行器内部串联调用时简单直接，因此 {@link LinearWorkflowExecutor} 和
     * {@link DagWorkflowExecutor} 目前都使用此模式。</p>
     *
     * @param record      失败 Todo 的执行记录
     * @param currentPlan 当前 Plan
     * @param context     已完成 Todo 的执行上下文（todoId → record）
     * @param userGoal    用户原始目标，单独模式下需作为 payload 字段显式传入
     * @param model       用于决策的 ChatModel
     * @return 判定结果；LLM 调用异常或 System Prompt 为空时退回 FAIL
     */
    public JudgeDecision judge(TodoExecutionRecord record,
                               TodoPlan currentPlan,
                               Map<String, TodoExecutionRecord> context,
                               String userGoal,
                               ChatModel model) {
        String systemPrompt = promptService.planJudgeRuntimeSystemPrompt();
        if (systemPrompt == null || systemPrompt.isBlank()) {
            log.debug("PlanJudge system prompt is empty, defaulting to FAIL");
            return JudgeDecision.FAIL;
        }

        Map<String, Object> payload = buildJudgePayload(record, currentPlan, context, userGoal);
        String userMessage = safeWrite(payload);

        // 独立模式：System + 单条 User，构造最小化对话
        List<ChatMessage> messages = List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(promptService.dynamicContextPrefix() + "\n" + userMessage)
        );

        try {
            ChatResponse response = model.chat(messages);
            String text = response.aiMessage() == null ? "" : nvl(response.aiMessage().text());
            return parseDecision(text);
        } catch (Exception e) {
            log.warn("PlanJudge LLM call failed: {}", e.getMessage());
            return JudgeDecision.FAIL;
        }
    }

    /**
     * 构造提交给 LLM 的诊断 payload。
     *
     * <p>包含以下字段（用于 LLM 推断决策）：</p>
     * <ul>
     *   <li>{@code user_goal}（可选）— 仅独立模式传入；ReAct 模式下已在共享上下文中。</li>
     *   <li>{@code failed_record} — 失败 Todo 的关键字段（success、summary、output 前 500 字预览、failureCategory）。
     *       output 做了 preview 截断，避免 LLM 输入过大。</li>
     *   <li>{@code current_plan_size} — 当前 Plan 的 Todo 数量，让 LLM 评估 patch 的成本与可行性。</li>
     *   <li>{@code completed_context_keys} — 已完成 Todo 的 ID 集合（仅 key），让 LLM 看到执行进度。</li>
     * </ul>
     *
     * @param record      失败 Todo 的执行记录
     * @param currentPlan 当前 Plan
     * @param context     已完成 Todo 的执行上下文（仅取 keys 写入 payload）
     * @param userGoal    用户原始目标；为 null 时不写入（ReAct 累积模式下使用）
     * @return 用于 LLM 输入的 payload Map
     */
    private Map<String, Object> buildJudgePayload(TodoExecutionRecord record,
                                                   TodoPlan currentPlan,
                                                   Map<String, TodoExecutionRecord> context,
                                                   String userGoal) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (userGoal != null) {
            payload.put("user_goal", nvl(userGoal));
        }
        payload.put("failed_record", Map.of(
                "success", record.isSuccess(),
                "summary", nvl(record.getSummary()),
                "output_preview", preview(record.getOutput()),
                "failure_category", nvl(record.getFailureCategory())
        ));
        payload.put("current_plan_size", currentPlan.getItems() == null ? 0 : currentPlan.getItems().size());
        payload.put("completed_context_keys", context == null ? List.of() : context.keySet());
        return payload;
    }

    /**
     * 将 LLM 的输出解析为 {@link JudgeDecision} 枚举。
     *
     * <p>包级可见以便单元测试。解析策略：</p>
     * <ol>
     *   <li>尝试从输出中提取首个 {...} JSON 片段，读取 {@code decision} 字段。</li>
     *   <li>JSON 解析失败时，退化为对原始文本做关键字匹配（详见 {@link #parseDecisionString}）。</li>
     *   <li>空文本或无法识别的关键字一律返回 {@link JudgeDecision#FAIL}（保守策略）。</li>
     * </ol>
     *
     * @param text LLM 原始输出
     * @return 解析后的判定枚举
     */
    JudgeDecision parseDecision(String text) {
        if (text == null || text.isBlank()) {
            return JudgeDecision.FAIL;
        }
        String json = extractJsonFromResponse(text);
        if (json != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
                Object decision = parsed.get("decision");
                if (decision != null) {
                    return parseDecisionString(String.valueOf(decision));
                }
            } catch (Exception e) {
                log.debug("Failed to parse judge JSON response, trying raw text");
            }
        }
        // 退化路径：直接对原始文本做关键字匹配
        return parseDecisionString(text.trim());
    }

    /**
     * 关键字匹配：将字符串转为大写后按优先级查找枚举名子串。
     *
     * <p>顺序非常重要：</p>
     * <ul>
     *   <li>{@code ABORT} 必须先于 {@code FAIL} 判断（避免在 LLM 输出 "ABORT" 时也包含 "FAIL" 字样而被误判）。</li>
     *   <li>{@code CONTINUE_WITH_RECOVERY_PARAMS} 必须先于 {@code CONTINUE}（前者包含后者）。</li>
     *   <li>裸 {@code CONTINUE} 等价于 {@code CONTINUE_WITH_RECOVERY_PARAMS}（向后兼容 LLM 输出的简写形式）。</li>
     * </ul>
     *
     * @param raw LLM 输出的决策字符串
     * @return 匹配到的枚举；未匹配到任何关键字时返回 FAIL
     */
    private JudgeDecision parseDecisionString(String raw) {
        String upper = nvl(raw).trim().toUpperCase();
        // ABORT 优先判断（避免被 FAIL 包含）
        if (upper.contains("ABORT")) {
            return JudgeDecision.ABORT;
        }
        if (upper.contains("PATCH_PLAN")) {
            return JudgeDecision.PATCH_PLAN;
        }
        if (upper.contains("FALLBACK_TO_LINEAR")) {
            return JudgeDecision.FALLBACK_TO_LINEAR;
        }
        if (upper.contains("CONTINUE_WITH_RECOVERY_PARAMS")) {
            return JudgeDecision.CONTINUE_WITH_RECOVERY_PARAMS;
        }
        if (upper.contains("CONTINUE")) {
            return JudgeDecision.CONTINUE_WITH_RECOVERY_PARAMS;
        }
        if (upper.contains("RETRY")) {
            return JudgeDecision.RETRY;
        }
        // 默认 FAIL（向后兼容）
        return JudgeDecision.FAIL;
    }

    /**
     * 从 LLM 输出中粗暴提取首个被 '{' 和最后一个 '}' 包围的子串作为 JSON 候选。
     *
     * <p>这是一种宽松提取：LLM 可能在 JSON 外还输出说明文字（如 markdown 代码围栏、推理过程），
     * 我们只取最大括号区间。若失败由上层做关键字回退。</p>
     *
     * @param text LLM 原始输出
     * @return JSON 子串；找不到 '{' '}' 配对时返回 null
     */
    private String extractJsonFromResponse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return null;
    }

    /**
     * 将文本截断为最多 500 字符，用于 payload 中的 output 预览。
     *
     * <p>失败 Todo 的原始 output 可能很长（如完整的 LLM 推理或工具结果），直接全量塞给 Judge LLM
     * 会显著推高 token 用量；500 字符通常足够让 LLM 判断失败类型。</p>
     */
    private String preview(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    /** 空安全：null 转为空字符串。 */
    private String nvl(String text) {
        return text == null ? "" : text;
    }

    /**
     * 安全的 JSON 序列化：序列化失败时返回 {@code "{}"}，避免破坏后续提示词拼接。
     */
    private String safeWrite(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }
}
