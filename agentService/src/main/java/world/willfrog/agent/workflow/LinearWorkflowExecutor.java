package world.willfrog.agent.workflow;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import world.willfrog.agent.context.AgentContext;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.model.AgentRunStatus;
import world.willfrog.agent.service.AgentObservabilityService;
import world.willfrog.agent.service.AgentCitationService;
import world.willfrog.agent.service.AgentEventService;
import world.willfrog.agent.service.AgentPromptService;
import world.willfrog.agent.service.AgentRunStateStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ReAct 模式的线性（串行）工作流执行器。
 *
 * <h3>在整体架构中的位置</h3>
 * 本执行器是 {@link WorkflowExecutor} 接口的两种实现之一（另一个是 {@link DagWorkflowExecutor}）。
 * 由 {@link world.willfrog.agent.service.AgentRunExecutor} 在 Plan 生成后通过
 * {@link WorkflowExecutorFactory} 选择本执行器或 DAG 执行器。
 *
 * <h3>核心执行流程（{@link #execute}）</h3>
 * <ol>
 *   <li><b>初始化</b>：从 Plan 中提取待执行项列表，构建可用工具名称白名单。</li>
 *   <li><b>遍历执行</b>：按 sequence 顺序依次处理每个 TodoItem：
 *     <ul>
 *       <li>跳过已完成的 Todo（可能由 Plan Patch 引入）。</li>
 *       <li>检查全局工具调用次数上限，超限则终止。</li>
 *       <li>为每个 Todo 构建 {@link ReactTodoExecutor.TodoExecutionContext}。</li>
 *       <li>调用 {@link ReactTodoExecutor#executeWithObservability} 执行 ReAct 循环。</li>
 *       <li>成功：收集结果到已完成列表，继续下一个。</li>
 *       <li>失败：进入失败恢复流程。</li>
 *     </ul>
 *   </li>
 *   <li><b>失败恢复</b>（仅当 enablePlanPatch=true 时启用）：
 *     <ul>
 *       <li>先检查 run 是否已被外部取消/失败，若是则跳过 patch 避免浪费 LLM 资源。</li>
 *       <li>调用 {@link PlanJudge} 对失败做出判断：RETRY / CONTINUE_WITH_RECOVERY_PARAMS / PATCH_PLAN / FAIL / ABORT。</li>
 *       <li>RETRY / CONTINUE_WITH_RECOVERY_PARAMS：不修改 Plan，直接原地重试当前 Todo（有次数上限）。</li>
 *       <li>PATCH_PLAN：调用 {@link PatchPlanner} 让 LLM 生成 PlanPatch，
 *           通过 {@link PlanPatcher} 应用到当前 Plan，然后继续执行（有总 patch 次数上限）。</li>
 *       <li>FAIL / ABORT：立即终止执行并返回失败。</li>
 *     </ul>
 *   </li>
 *   <li><b>生成最终回答</b>：所有 Todo 执行完毕后，将已完成 Todo 的 summary/output +
 *       引用表注入提示词，调用 LLM 生成可直接展示的 Markdown 最终答案。</li>
 * </ol>
 *
 * <h3>Plan Patch 机制</h3>
 * 当某个 Todo 执行失败时，Plan Patch 允许 LLM 重新审视现有 Plan 并提出修改：
 * <ul>
 *   <li>{@code REPLACE} 类型的 patch 会用新 TodoItem 替换失败的节点。</li>
 *   <li>应用 patch 后，会重新构建待处理列表（过滤掉已完成的，剩余按 sequence 排序）。</li>
 *   <li>每个 run 最多应用 {@code maxPatchesPerRun} 次 patch（默认 2 次）。</li>
 *   <li>单 Todo 最多原地重试 {@code maxRetriesPerTodoAfterJudge} 次（默认 2 次）。</li>
 * </ul>
 *
 * @see DagWorkflowExecutor
 * @see ReactTodoExecutor
 * @see PlanJudge
 * @see PlanPatcher
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LinearWorkflowExecutor implements WorkflowExecutor {

    /** 事件流服务，用于追加 run 执行过程中的关键事件 */
    private final AgentEventService eventService;
    /** 提示词服务，提供 System Prompt、最终回答指令等 */
    private final AgentPromptService promptService;
    /** 单个 Todo 的 ReAct 执行器 */
    private final ReactTodoExecutor reactTodoExecutor;
    /** Plan 质量判断器，对失败的 Todo 输出判断决策（RETRY/PATCH/FAIL 等） */
    private final PlanJudge planJudge;
    /** Patch 计划生成器，让 LLM 根据失败信息生成 Plan 修正方案 */
    private final PatchPlanner patchPlanner;
    /** Patch 应用器，将 PlanPatch 应用到 TodoPlan 得到修正后的新 Plan */
    private final PlanPatcher planPatcher;
    /** Run 级 Redis 状态缓存，用于在执行过程中检查 run 状态 */
    private final AgentRunStateStore stateStore;
    /** 引用来源服务，从已完成任务中提取引用并构建引用表 */
    private final AgentCitationService citationService;

    /** 整个 Run 的全局工具调用次数上限（默认 20 次），防止失控消耗 */
    @Value("${agent.flow.workflow.max-tool-calls:20}")
    private int defaultMaxToolCalls;

    /** Plan Judge 判定 RETRY 后，单 Todo 最多原地重试的次数（默认 2 次） */
    @Value("${agent.flow.plan-patch.max-retries-per-todo:2}")
    private int maxRetriesPerTodoAfterJudge;

    /** 整个 Run 中 Plan Patch（修改 Plan 结构）的总次数上限（默认 2 次） */
    @Value("${agent.flow.plan-patch.max-patches-per-run:2}")
    private int maxPatchesPerRun;

    /** 观测阶段名常量 */
    private static final String PHASE_LINEAR_EXECUTION = "linear_execution";

    /**
     * 执行线性工作流。
     *
     * <p>将 Todo Plan 中的每个 TodoItem 按 sequence 顺序逐个执行。
     * 支持失败时的 Plan Patch 自动修复和原地重试。</p>
     *
     * @param request 工作流请求，含 Run、Plan、ChatModel、工具定义等
     * @return 执行结果，含最终答案、成功/失败标记、引用表等
     */
    @Override
    public WorkflowExecutionResult execute(WorkflowRequest request) {
        AgentRun run = request.getRun();
        String runId = run.getId();
        String userId = request.getUserId();
        TodoPlan currentPlan = request.getPlan();

        eventService.append(runId, userId, "REACT_LINEAR_EXECUTION_STARTED", Map.of(
                "items_count", currentPlan.getItems().size(),
                "plan_patch_enabled", request.isEnablePlanPatch()
        ));

        // 构建可用工具名称白名单（LLM 只能调用此集合内的工具）
        Set<String> availableTools = request.getToolSpecifications().stream()
                .map(ToolSpecification::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        String userGoal = request.getUserGoal();

        // ── 执行状态追踪 ──
        List<CompletedTodoInfo> completedTodos = new ArrayList<>();
        /** 已有数据集 ID → 路径 映射，跨 Todo 共享 */
        Map<String, String> datasetRefs = new HashMap<>();
        /** 单 Todo 的原地重试次数追踪 */
        Map<String, Integer> retryCountByTodo = new HashMap<>();
        /** 执行上下文（供 PlanJudge 诊断失败原因） */
        Map<String, TodoExecutionRecord> executionContext = new LinkedHashMap<>();
        /** 已完成 Todo ID 集合（用于判断是否跳过） */
        Set<String> completedTodoIds = new LinkedHashSet<>();

        int totalToolCalls = 0;
        int patchCount = 0;
        List<TodoItem> pendingItems = new ArrayList<>(currentPlan.getItems());
        int index = 0;

        while (index < pendingItems.size()) {
            TodoItem item = pendingItems.get(index);

            // Plan Patch 后可能产生与已完成 Todo 重复的项，跳过
            if (completedTodoIds.contains(item.getId())) {
                index++;
                continue;
            }

            // 检查全局工具调用次数上限
            if (totalToolCalls >= defaultMaxToolCalls) {
                eventService.append(runId, userId, "TOOL_CALL_LIMIT_REACHED", Map.of("limit", defaultMaxToolCalls));
                return buildFailureResult(completedTodos, "Tool call limit reached");
            }

            eventService.append(runId, userId, "TODO_STARTED", Map.of(
                    "todo_id", item.getId(),
                    "description", item.getDescription()
            ));

            // 为当前 Todo 构建执行上下文
            ReactTodoExecutor.TodoExecutionContext todoContext = ReactTodoExecutor.TodoExecutionContext.builder()
                    .userGoal(userGoal)
                    .availableTools(availableTools)
                    .toolSpecifications(request.getToolSpecifications())
                    .completedTodos(new ArrayList<>(completedTodos))
                    .datasetRefs(new HashMap<>(datasetRefs))
                    .build();

            // 执行 ReAct 循环
            ReactTodoExecutor.TodoExecutionRecord record = reactTodoExecutor.executeWithObservability(
                    item.getDescription(),
                    todoContext,
                    request.getModel(),
                    runId,
                    PHASE_LINEAR_EXECUTION
            );
            totalToolCalls += record.getToolCallsUsed();

            // ── 执行成功 ──
            if (record.isSuccess()) {
                // 将本次执行产生的 dataset 引用合并到全局映射
                datasetRefs.putAll(todoContext.getDatasetRefs());
                completedTodoIds.add(item.getId());
                completedTodos.add(CompletedTodoInfo.builder()
                        .todoId(item.getId())
                        .description(item.getDescription())
                        .output(record.getOutput())
                        .summary(record.getSummary())
                        .messageHistory(record.getMessageHistory())
                        .build());
                executionContext.put(item.getId(), toLegacyRecord(record));
                eventService.append(runId, userId, "TODO_COMPLETED", Map.of(
                        "todo_id", item.getId(),
                        "tool_calls_used", record.getToolCallsUsed(),
                        "summary", nvl(record.getSummary())
                ));
                index++;
                continue;
            }

            // ── 执行失败 ──
            eventService.append(runId, userId, "TODO_FAILED", Map.of(
                    "todo_id", item.getId(),
                    "summary", nvl(record.getSummary())
            ));
            TodoExecutionRecord failedRecord = toLegacyRecord(record);

            // 若未启用 Plan Patch，直接返回失败
            if (!request.isEnablePlanPatch()) {
                return buildFailureResult(completedTodos, nvl(record.getSummary()));
            }

            // 检查 run 是否已被外部取消/失败，若是则跳过 patch 避免无效 LLM 消耗
            Optional<String> runStatus = stateStore.loadRunStatus(runId);
            if (runStatus.isPresent() &&
                    (runStatus.get().equals(AgentRunStatus.CANCELED.name()) ||
                     runStatus.get().equals(AgentRunStatus.FAILED.name()))) {
                log.info("Run {} has been {}, skipping plan patch", runId, runStatus.get());
                return buildFailureResult(completedTodos,
                        "run_" + runStatus.get().toLowerCase() + ":" + nvl(record.getSummary()));
            }

            // 调用 Plan Judge 对失败做出判断
            JudgeDecision decision = planJudge.judge(
                    failedRecord,
                    currentPlan,
                    executionContext,
                    userGoal,
                    request.getModel()
            );

            // Judge 判定：原地重试（不修改 Plan 结构）
            if (decision == JudgeDecision.RETRY || decision == JudgeDecision.CONTINUE_WITH_RECOVERY_PARAMS) {
                int retries = retryCountByTodo.getOrDefault(item.getId(), 0);
                if (retries >= maxRetriesPerTodoAfterJudge) {
                    return buildFailureResult(completedTodos,
                            "todo_retry_exhausted:" + item.getId() + ":" + nvl(record.getSummary()));
                }
                retryCountByTodo.put(item.getId(), retries + 1);
                eventService.append(runId, userId, "TODO_RETRY_SCHEDULED", Map.of(
                        "todo_id", item.getId(),
                        "retry_count", retries + 1,
                        "max_retries", maxRetriesPerTodoAfterJudge
                ));
                // 不递增 index，下一轮循环依旧处理同一个 Todo
                continue;
            }

            // Judge 判定：修改 Plan 结构
            if (decision == JudgeDecision.PATCH_PLAN) {
                if (patchCount >= maxPatchesPerRun) {
                    return buildFailureResult(completedTodos,
                            "plan_patch_exhausted:" + item.getId() + ":" + nvl(record.getSummary()));
                }
                // 让 LLM 生成 Plan 修正方案
                PlanPatch patch = patchPlanner.generatePatch(
                        failedRecord,
                        currentPlan,
                        executionContext,
                        userGoal,
                        request.getModel()
                );
                if (patch == null) {
                    return buildFailureResult(completedTodos,
                            "plan_patch_generation_failed:" + item.getId() + ":" + nvl(record.getSummary()));
                }
                // 应用 patch 得到修正后的新 Plan
                TodoPlan patchedPlan = planPatcher.applyPatch(currentPlan, patch);
                if (patchedPlan == null || patchedPlan.getItems() == null || patchedPlan.getItems().isEmpty()) {
                    return buildFailureResult(completedTodos,
                            "plan_patch_apply_failed:" + item.getId() + ":" + nvl(record.getSummary()));
                }
                patchCount++;
                currentPlan = patchedPlan;
                // 重建待处理列表：过滤掉已完成的，剩余按 sequence 排序
                pendingItems = patchedPlan.getItems().stream()
                        .filter(t -> !completedTodoIds.contains(t.getId()))
                        .sorted(java.util.Comparator.comparingInt(TodoItem::getSequence))
                        .collect(Collectors.toCollection(ArrayList::new));
                // 尝试定位当前失败的 Todo 在新列表中的位置，继续处理
                index = findTodoIndex(pendingItems, item.getId());
                eventService.append(runId, userId, "PLAN_PATCH_APPLIED", Map.of(
                        "todo_id", item.getId(),
                        "patch_type", patch.getPatchType().name(),
                        "patch_count", patchCount,
                        "max_patches", maxPatchesPerRun
                ));
                continue;
            }

            // Judge 判定：不可恢复的失败，立即终止
            if (decision == JudgeDecision.FAIL || decision == JudgeDecision.ABORT) {
                return buildFailureResult(completedTodos, nvl(record.getSummary()));
            }

            // 默认：返回失败
            return buildFailureResult(completedTodos, nvl(record.getSummary()));
        }

        // ── 所有 Todo 执行完毕，生成最终回答 ──
        // Final-Answer 阶段可以使用独立模型（若 run 请求中配置了 final_answer 专用模型）
        ChatModel finalAnswerModel = request.getFinalAnswerModel() == null ? request.getModel() : request.getFinalAnswerModel();
        FinalAnswerResult finalAnswer = generateFinalAnswer(
                userGoal,
                completedTodos,
                finalAnswerModel,
                request.getFinalAnswerReasoningEffort());
        return WorkflowExecutionResult.builder()
                .success(true)
                .finalAnswer(finalAnswer.answer())
                .citationMap(finalAnswer.citationMap())
                .completedItems(new ArrayList<>())
                .toolCallsUsed(totalToolCalls)
                .build();
    }

    /**
     * 在待处理列表中根据 todoId 查找对应的索引位置。
     *
     * <p>Plan Patch 后，待处理列表会被重建并重新排序。由于失败的 Todo 可能在
     * 新 Plan 中仍然存在（等待重试），需要定位其在新列表中的位置以继续处理。</p>
     *
     * @param items  待处理列表
     * @param todoId 待查找的 Todo ID
     * @return 索引位置，未找到时返回 0（从头部开始处理）
     */
    private int findTodoIndex(List<TodoItem> items, String todoId) {
        if (items == null || items.isEmpty() || todoId == null || todoId.isBlank()) {
            return 0;
        }
        for (int i = 0; i < items.size(); i++) {
            TodoItem item = items.get(i);
            if (item != null && todoId.equals(item.getId())) {
                return i;
            }
        }
        return 0;
    }

    /**
     * 将 ReactTodoExecutor 的执行记录转为轻量的 TodoExecutionRecord，
     * 供 PlanJudge 和 PatchPlanner 使用。
     *
     * <p>TODO: 这里的数据模型存在重复——ReactTodoExecutor.TodoExecutionRecord 和
     * TodoExecutionRecord 几乎是同构的，后续应考虑统一。</p>
     */
    private TodoExecutionRecord toLegacyRecord(ReactTodoExecutor.TodoExecutionRecord record) {
        if (record == null) {
            return TodoExecutionRecord.builder().success(false).summary("empty_record").build();
        }
        return TodoExecutionRecord.builder()
                .success(record.isSuccess())
                .output(nvl(record.getOutput()))
                .summary(nvl(record.getSummary()))
                .toolCallsUsed(record.getToolCallsUsed())
                .build();
    }

    /**
     * 汇总所有已完成 Todo 的结果，调用 LLM 生成最终回答。
     *
     * <p>生成过程：</p>
     * <ol>
     *   <li>从已完成 Todo 的输出中提取引用来源，构建去重编号后的引用表。</li>
     *   <li>组装提示词：System Prompt（dagReactSystemPrompt）+
     *       最终回答指令 + 用户问题 + 已完成任务摘要 + 引用表。</li>
     *   <li>设置 phase=summarizing 和 stage=final_answer 后调用 LLM。</li>
     *   <li>调用前保存当前 AgentContext 的 phase/stage/reasoningEffort，
     *       调用后在 finally 中恢复，避免污染后续流程。</li>
     * </ol>
     *
     * @param userGoal                   用户原始问题
     * @param completedTodos             所有已完成的 Todo 信息
     * @param model                      用于生成最终回答的 ChatModel
     * @param finalAnswerReasoningEffort Final-Answer 阶段的 reasoning effort 配置
     * @return 最终回答和引用表
     */
    private FinalAnswerResult generateFinalAnswer(String userGoal,
                                                  List<CompletedTodoInfo> completedTodos,
                                                  ChatModel model,
                                                  String finalAnswerReasoningEffort) {
        // 构建引用来源映射表（按 URL 去重、重新编号）
        AgentCitationService.CitationMap citationMap = citationService.buildCitationMap(completedTodos);
        try {
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new SystemMessage(promptService.dagReactSystemPrompt()));

            // 组装上下文提示词
            StringBuilder context = new StringBuilder();
            context.append(promptService.finalAnswerStageInstruction()).append("\n\n");
            context.append(promptService.dynamicContextPrefix()).append("\n\n");
            context.append("用户问题：").append(userGoal).append("\n\n");
            context.append("已完成的任务：\n");
            for (CompletedTodoInfo todo : completedTodos) {
                context.append(String.format("- %s: %s\n", todo.getDescription(), todo.getSummary()));
                if (todo.getOutput() != null && !todo.getOutput().isEmpty()) {
                    context.append("  输出: ").append(todo.getOutput()).append("\n");
                }
            }
            // 注入引用表：告诉 LLM 每个 [N] 编号对应的来源 URL
            context.append(citationService.buildPromptBlock(citationMap));
            context.append("\n请根据以上所有任务结果，生成对用户问题可直接展示的最终回答。");
            context.append("\n请直接输出 Markdown，不要把回答包在 JSON 或代码块里。若使用搜索证据，请在相关句子后标注引用序号，例如 [1] [2]。");
            messages.add(new UserMessage(context.toString()));

            // 保存并切换 AgentContext 的 phase/stage
            String previousPhase = AgentContext.getPhase();
            String previousStage = AgentContext.getStage();
            String previousReasoningEffort = AgentContext.getReasoningEffort();
            AgentContext.setPhase(AgentObservabilityService.PHASE_SUMMARIZING);
            AgentContext.setStage("final_answer");
            if (finalAnswerReasoningEffort != null && !finalAnswerReasoningEffort.isBlank()) {
                AgentContext.setReasoningEffort(finalAnswerReasoningEffort);
            }
            ChatResponse response;
            try {
                response = model.chat(messages);
            } finally {
                // 恢复 AgentContext 的原始 phase/stage/reasoningEffort
                if (previousPhase == null || previousPhase.isBlank()) {
                    AgentContext.clearPhase();
                } else {
                    AgentContext.setPhase(previousPhase);
                }
                if (previousStage == null || previousStage.isBlank()) {
                    AgentContext.clearStage();
                } else {
                    AgentContext.setStage(previousStage);
                }
                if (previousReasoningEffort == null || previousReasoningEffort.isBlank()) {
                    AgentContext.clearReasoningEffort();
                } else {
                    AgentContext.setReasoningEffort(previousReasoningEffort);
                }
            }
            return new FinalAnswerResult(response.aiMessage() != null ? response.aiMessage().text() : "", citationMap);
        } catch (Exception e) {
            log.error("Failed to generate final answer", e);
            return new FinalAnswerResult("无法生成回答: " + e.getMessage(), citationMap);
        }
    }

    /**
     * 构建失败结果。
     *
     * <p>将所有已完成 Todo 的 output 拼接为 finalAnswer（确保即使失败，
     * 用户也能看到已经完成了哪些工作），并基于已完成 Todo 构建引用表。</p>
     *
     * @param completedTodos 已完成的任务列表
     * @param errorMessage   失败原因
     * @return 失败的工作流执行结果
     */
    private WorkflowExecutionResult buildFailureResult(List<CompletedTodoInfo> completedTodos, String errorMessage) {
        StringBuilder combinedOutput = new StringBuilder();
        for (CompletedTodoInfo todo : completedTodos) {
            if (todo.getOutput() != null && !todo.getOutput().isEmpty()) {
                combinedOutput.append(todo.getOutput()).append("\n");
            }
        }
        return WorkflowExecutionResult.builder()
                .success(false)
                .finalAnswer(combinedOutput.toString())
                .failureReason(errorMessage)
                .citationMap(citationService.buildCitationMap(completedTodos))
                .completedItems(new ArrayList<>())
                .build();
    }

    /** 空安全：null 转为空字符串。 */
    private String nvl(String value) {
        return value == null ? "" : value;
    }

    /** 最终回答及其引用表的不可变记录。 */
    private record FinalAnswerResult(String answer, AgentCitationService.CitationMap citationMap) {
    }
}