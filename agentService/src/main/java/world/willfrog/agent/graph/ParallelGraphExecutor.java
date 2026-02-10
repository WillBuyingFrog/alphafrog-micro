package world.willfrog.agent.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.mapper.AgentRunMapper;
import world.willfrog.agent.model.AgentRunStatus;
import world.willfrog.agent.service.AgentEventService;
import world.willfrog.agent.service.AgentLlmRequestSnapshotBuilder;
import world.willfrog.agent.service.AgentObservabilityService;
import world.willfrog.agent.service.AgentPromptService;
import world.willfrog.agent.service.AgentRunStateStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

@Component
@RequiredArgsConstructor
@Slf4j
/**
 * 并行图执行器（LangGraph4j）。
 * <p>
 * 核心职责：
 * 1. 生成并校验并行计划；
 * 2. 执行并行任务并聚合结果；
 * 3. 在可观测事件中输出“并行是否接管、为何回退”的决策信息；
 * 4. 在满足条件时将 run 直接标记为 COMPLETED。
 */
public class ParallelGraphExecutor {

    /** 具体的并行任务调度执行器。 */
    private final ParallelTaskExecutor taskExecutor;
    /** 计划结构与约束校验器。 */
    private final ParallelPlanValidator planValidator = new ParallelPlanValidator();
    /** run 主表更新。 */
    private final AgentRunMapper runMapper;
    /** 事件写入服务。 */
    private final AgentEventService eventService;
    /** Redis 状态缓存（计划、任务进度、run 状态）。 */
    private final AgentRunStateStore stateStore;
    /** Prompt 配置服务。 */
    private final AgentPromptService promptService;
    /** 可观测指标聚合服务。 */
    private final AgentObservabilityService observabilityService;
    /** LLM 原始请求快照构建器。 */
    private final AgentLlmRequestSnapshotBuilder llmRequestSnapshotBuilder;
    /** JSON 序列化/反序列化。 */
    private final ObjectMapper objectMapper;

    /** 是否启用并行图总开关。 */
    @Value("${agent.flow.parallel.enabled:true}")
    private boolean enabled;

    /** 计划允许的最大任务数。 */
    @Value("${agent.flow.parallel.max-tasks:6}")
    private int maxTasks;

    /** 单个 sub_agent 允许的最大步数。 */
    @Value("${agent.flow.parallel.sub-agent-max-steps:6}")
    private int subAgentMaxSteps;

    /** 允许的“无依赖并行任务”上限，-1 表示不限制。 */
    @Value("${agent.flow.parallel.max-parallel-tasks:-1}")
    private int maxParallelTasks;

    /** 允许的 sub_agent 任务上限，-1 表示不限制。 */
    @Value("${agent.flow.parallel.max-sub-agents:-1}")
    private int maxSubAgents;

    /**
     * 并行图执行器是否启用。
     *
     * @return true 表示启用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 执行并行图主流程。
     * <p>
     * 返回值语义：
     * 1. true：并行流程已接管并完成（或在 HITL 场景下按约定结束）；
     * 2. false：并行流程未接管，调用方应回退串行执行。
     *
     * @param run                当前 run
     * @param userId             用户 ID
     * @param userGoal           用户目标
     * @param model              聊天模型
     * @param toolSpecifications 工具声明
     * @return 是否由并行流程处理完成
     */
    public boolean execute(AgentRun run,
                           String userId,
                           String userGoal,
                           ChatLanguageModel model,
                           List<ToolSpecification> toolSpecifications,
                           String endpointName,
                           String endpointBaseUrl,
                           String modelName) {
        if (!enabled) {
            return false;
        }

        try {
            Set<String> toolWhitelist = toolSpecifications.stream()
                    .map(ToolSpecification::name)
                    .collect(Collectors.toSet());

            var graph = buildGraph(run, userId, userGoal, model, toolWhitelist, endpointName, endpointBaseUrl, modelName);
            Map<String, Object> initial = Map.of(
                    "user_goal", userGoal
            );

            ParallelGraphState finalState = null;
            for (var event : graph.stream(initial)) {
                finalState = event.state();
            }
            // finalState 为空通常表示图执行过程被中断或未产生可用状态。
            if (finalState == null) {
                emitParallelDecision(run.getId(), userId, false, false, false, true, false, "final_state_missing");
                return false;
            }
            boolean planValid = finalState.planValid().orElse(false);
            boolean allDone = finalState.allDone().orElse(false);
            boolean paused = finalState.paused().orElse(false);
            String finalAnswer = finalState.finalAnswer().orElse("");
            // 计划不合法：明确记录决策，回退串行。
            if (!planValid) {
                emitParallelDecision(run.getId(), userId, false, allDone, paused, finalAnswer.isBlank(), false, "plan_invalid");
                return false;
            }
            // 任务尚未完成：只有 paused 场景才算“已由并行流程处理到等待态”。
            if (!allDone) {
                boolean handled = paused;
                emitParallelDecision(
                        run.getId(),
                        userId,
                        true,
                        false,
                        paused,
                        finalAnswer.isBlank(),
                        handled,
                        paused ? "run_paused" : "tasks_not_all_done"
                );
                return paused;
            }
            // 任务都做完但没有最终结论，视为并行流程未成功接管。
            if (finalAnswer.isBlank()) {
                emitParallelDecision(run.getId(), userId, true, true, paused, true, false, "final_answer_blank");
                return false;
            }

            Map<String, Object> snapshot = new HashMap<>();
            snapshot.put("user_goal", userGoal);
            snapshot.put("plan", finalState.planJson().orElse("{}"));
            snapshot.put("task_results", finalState.taskResults().orElse(Map.of()));
            snapshot.put("answer", finalAnswer);

            String snapshotJson = objectMapper.writeValueAsString(snapshot);
            snapshotJson = observabilityService.attachObservabilityToSnapshot(run.getId(), snapshotJson, AgentRunStatus.COMPLETED);
            runMapper.updateSnapshot(run.getId(), userId, AgentRunStatus.COMPLETED, snapshotJson, true, null);
            eventService.append(run.getId(), userId, "COMPLETED", Map.of("answer", finalAnswer));
            stateStore.markRunStatus(run.getId(), AgentRunStatus.COMPLETED.name());
            emitParallelDecision(run.getId(), userId, true, true, paused, false, true, "completed");
            return true;
        } catch (Exception e) {
            // 决策事件的异常路径也尽量记录，便于线上排障。
            try {
                emitParallelDecision(run.getId(), userId, false, false, false, true, false, "exception:" + e.getClass().getSimpleName());
            } catch (Exception eventEx) {
                log.warn("Failed to append PARALLEL_GRAPH_DECISION in exception path: runId={}", run.getId(), eventEx);
            }
            log.warn("Parallel graph execution failed", e);
            return false;
        }
    }

    /**
     * 输出并行决策埋点事件，便于定位“为何回退串行/为何未产出并行任务事件”。
     *
     * @param runId            任务 ID
     * @param userId           用户 ID
     * @param planValid        计划是否有效
     * @param allDone          任务是否全部完成
     * @param paused           是否处于暂停态
     * @param finalAnswerBlank 最终答案是否为空
     * @param handled          并行流程是否视作已处理
     * @param reason           决策原因
     */
    private void emitParallelDecision(String runId,
                                      String userId,
                                      boolean planValid,
                                      boolean allDone,
                                      boolean paused,
                                      boolean finalAnswerBlank,
                                      boolean handled,
                                      String reason) {
        eventService.append(runId, userId, "PARALLEL_GRAPH_DECISION", Map.of(
                "plan_valid", planValid,
                "all_done", allDone,
                "paused", paused,
                "final_answer_blank", finalAnswerBlank,
                "handled", handled,
                "reason", reason
        ));
    }

    /**
     * 构建 LangGraph4j 编排图。
     *
     * @param run           当前 run
     * @param userId        用户 ID
     * @param userGoal      用户目标
     * @param model         聊天模型
     * @param toolWhitelist 可用工具白名单
     * @return 编译后的图对象
     */
    private CompiledGraph<ParallelGraphState> buildGraph(AgentRun run,
                                                         String userId,
                                                         String userGoal,
                                                         ChatLanguageModel model,
                                                         Set<String> toolWhitelist,
                                                         String endpointName,
                                                         String endpointBaseUrl,
                                                         String modelName) {
        try {
            var stateGraph = new StateGraph<>(ParallelGraphState.SCHEMA, ParallelGraphState::new)
                    .addNode("plan", node_async(state -> planNode(
                            run,
                            userId,
                            userGoal,
                            model,
                            toolWhitelist,
                            endpointName,
                            endpointBaseUrl,
                            modelName
                    )))
                    .addNode("execute", node_async(state -> executeNode(
                            run,
                            userId,
                            userGoal,
                            model,
                            toolWhitelist,
                            endpointName,
                            endpointBaseUrl,
                            modelName,
                            state
                    )))
                    .addNode("final", node_async(state -> finalNode(
                            run,
                            userId,
                            userGoal,
                            model,
                            endpointName,
                            endpointBaseUrl,
                            modelName,
                            state
                    )))
                    .addEdge(StateGraph.START, "plan")
                    .addEdge("plan", "execute")
                    .addEdge("execute", "final")
                    .addEdge("final", StateGraph.END);

            return stateGraph.compile();
        } catch (GraphStateException e) {
            throw new IllegalStateException("Failed to build LangGraph4j flow", e);
        }
    }

    /**
     * 规划节点：生成计划、校验计划并写入状态。
     *
     * @param run           当前 run
     * @param userId        用户 ID
     * @param userGoal      用户目标
     * @param model         聊天模型
     * @param toolWhitelist 可用工具白名单
     * @return 状态更新字段
     */
    private Map<String, Object> planNode(AgentRun run,
                                         String userId,
                                         String userGoal,
                                         ChatLanguageModel model,
                                         Set<String> toolWhitelist,
                                         String endpointName,
                                         String endpointBaseUrl,
                                         String modelName) {
        eventService.append(run.getId(), userId, "PLAN_STARTED", Map.of("run_id", run.getId()));

        String planText;
        String planJson;
        boolean usedStoredPlan = false;
        boolean override = stateStore.isPlanOverride(run.getId());
        Optional<String> stored = stateStore.loadPlan(run.getId());
        // Redis 未命中时回退到 DB 已持久化计划，支持恢复/续跑场景。
        if (stored.isEmpty() && run.getPlanJson() != null && !run.getPlanJson().isBlank() && !"{}".equals(run.getPlanJson().trim())) {
            stored = Optional.of(run.getPlanJson());
        }

        if (stored.isPresent()) {
            planJson = stored.get();
            planText = planJson;
            usedStoredPlan = true;
        } else {
            String prompt = buildPlannerPrompt(toolWhitelist, maxTasks, subAgentMaxSteps);
            List<dev.langchain4j.data.message.ChatMessage> plannerMessages = List.of(
                    new SystemMessage(prompt),
                    new UserMessage(userGoal)
            );
            long llmStartedAt = System.currentTimeMillis();
            Response<AiMessage> response = model.generate(plannerMessages);
            long llmDurationMs = System.currentTimeMillis() - llmStartedAt;
            planText = response.content().text();
            planJson = extractJson(planText);
            Map<String, Object> llmRequestSnapshot = llmRequestSnapshotBuilder.buildChatCompletionsRequest(
                    endpointName,
                    endpointBaseUrl,
                    modelName,
                    plannerMessages,
                    null,
                    Map.of("stage", "parallel_plan")
            );
            observabilityService.recordLlmCall(
                    run.getId(),
                    AgentObservabilityService.PHASE_PLANNING,
                    response.tokenUsage(),
                    llmDurationMs,
                    endpointName,
                    modelName,
                    null,
                    llmRequestSnapshot,
                    planText
            );
        }

        ParallelPlan plan = parsePlan(planJson);
        ParallelPlanValidator.Result validation = planValidator.validate(
                plan,
                toolWhitelist,
                maxTasks,
                subAgentMaxSteps,
                maxParallelTasks,
                maxSubAgents
        );

        boolean valid = validation.isValid();
        planJson = safeWrite(plan);
        runMapper.updatePlan(run.getId(), userId, AgentRunStatus.EXECUTING, planJson);
        stateStore.recordPlan(run.getId(), planJson, valid);
        stateStore.markRunStatus(run.getId(), AgentRunStatus.EXECUTING.name());
        observabilityService.addNodeCount(run.getId(), plan.getTasks() == null ? 0 : plan.getTasks().size());

        if (valid) {
            eventService.append(run.getId(), userId, "PLAN_CREATED", Map.of(
                    "plan", planJson,
                    "strategy", plan.getStrategy()
            ));
        } else {
            eventService.append(run.getId(), userId, "PLAN_INVALID", Map.of(
                    "reason", validation.getReason(),
                    "raw_plan", planText
            ));
        }

        if (usedStoredPlan) {
            eventService.append(run.getId(), userId, override ? "PLAN_OVERRIDE_USED" : "PLAN_REUSED", Map.of(
                    "plan", planJson
            ));
            if (override) {
                stateStore.clearPlanOverride(run.getId());
            }
        }

        Map<String, Object> update = new HashMap<>();
        update.put("plan_json", planJson);
        update.put("plan_valid", valid);
        return update;
    }

    /**
     * 执行节点：并发执行任务并汇总阶段结果。
     *
     * @param run           当前 run
     * @param userId        用户 ID
     * @param userGoal      用户目标
     * @param model         聊天模型
     * @param toolWhitelist 可用工具白名单
     * @param state         当前图状态
     * @return 状态更新字段
     */
    private Map<String, Object> executeNode(AgentRun run,
                                            String userId,
                                            String userGoal,
                                            ChatLanguageModel model,
                                            Set<String> toolWhitelist,
                                            String endpointName,
                                            String endpointBaseUrl,
                                            String modelName,
                                            ParallelGraphState state) {
        boolean planValid = state.planValid().orElse(false);
        if (!planValid) {
            return Map.of();
        }
        ParallelPlan plan = parsePlan(state.planJson().orElse("{}"));
        Map<String, ParallelTaskResult> existing = stateStore.loadTaskResults(run.getId());
        long startedAt = System.currentTimeMillis();
        Map<String, ParallelTaskResult> results = taskExecutor.execute(
                plan,
                run.getId(),
                userId,
                toolWhitelist,
                subAgentMaxSteps,
                userGoal,
                model,
                existing,
                endpointName,
                endpointBaseUrl,
                modelName
        );
        observabilityService.recordPhaseDuration(
                run.getId(),
                AgentObservabilityService.PHASE_PARALLEL_EXECUTION,
                System.currentTimeMillis() - startedAt
        );
        boolean allDone = isAllDone(plan, results);
        boolean paused = !eventService.isRunnable(run.getId(), userId);
        Map<String, Object> update = new HashMap<>();
        update.put("task_results", results);
        update.put("all_done", allDone);
        update.put("paused", paused);
        return update;
    }

    /**
     * 收敛节点：对并行任务结果进行总结，生成最终答案文本。
     *
     * @param run      当前 run
     * @param userId   用户 ID
     * @param userGoal 用户目标
     * @param model    聊天模型
     * @param state    当前图状态
     * @return 状态更新字段（包含 final_answer）
     */
    private Map<String, Object> finalNode(AgentRun run,
                                          String userId,
                                          String userGoal,
                                          ChatLanguageModel model,
                                          String endpointName,
                                          String endpointBaseUrl,
                                          String modelName,
                                          ParallelGraphState state) {
        boolean planValid = state.planValid().orElse(false);
        boolean allDone = state.allDone().orElse(false);
        if (!planValid || !allDone) {
            return Map.of("final_answer", "");
        }
        Map<String, ParallelTaskResult> results = state.taskResults().orElse(Map.of());
        String resultJson = safeWrite(results);
        String prompt = promptService.parallelFinalSystemPrompt();
        List<dev.langchain4j.data.message.ChatMessage> finalMessages = List.of(
                new SystemMessage(prompt),
                new UserMessage("目标: " + userGoal + "\n结果: " + resultJson)
        );
        long llmStartedAt = System.currentTimeMillis();
        Response<AiMessage> response = model.generate(finalMessages);
        long llmDurationMs = System.currentTimeMillis() - llmStartedAt;
        String answer = response.content().text();
        Map<String, Object> llmRequestSnapshot = llmRequestSnapshotBuilder.buildChatCompletionsRequest(
                endpointName,
                endpointBaseUrl,
                modelName,
                finalMessages,
                null,
                Map.of("stage", "parallel_final")
        );
        observabilityService.recordLlmCall(
                run.getId(),
                AgentObservabilityService.PHASE_SUMMARIZING,
                response.tokenUsage(),
                llmDurationMs,
                endpointName,
                modelName,
                null,
                llmRequestSnapshot,
                answer
        );
        return Map.of("final_answer", answer);
    }

    /**
     * 安全反序列化计划 JSON，失败时返回空计划对象。
     *
     * @param json 计划 JSON
     * @return 计划对象
     */
    private ParallelPlan parsePlan(String json) {
        try {
            return objectMapper.readValue(json, ParallelPlan.class);
        } catch (Exception e) {
            return new ParallelPlan();
        }
    }

    /**
     * 安全序列化对象，失败时返回空 JSON 对象。
     *
     * @param obj 任意对象
     * @return JSON 字符串
     */
    private String safeWrite(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 从模型输出文本中提取最外层 JSON 片段。
     *
     * @param text 模型原始输出
     * @return JSON 文本
     */
    private String extractJson(String text) {
        if (text == null) {
            return "{}";
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text.trim();
    }

    /**
     * 判断计划中的任务是否都已经产出结果。
     *
     * @param plan    计划
     * @param results 当前结果映射
     * @return true 表示全部完成
     */
    private boolean isAllDone(ParallelPlan plan, Map<String, ParallelTaskResult> results) {
        if (plan == null || plan.getTasks() == null || plan.getTasks().isEmpty()) {
            return true;
        }
        if (results == null || results.isEmpty()) {
            return false;
        }
        for (ParallelPlan.PlanTask task : plan.getTasks()) {
            if (!results.containsKey(task.getId())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 构建规划模型的系统提示词。
     *
     * @param toolWhitelist 工具白名单
     * @param maxTasks      最大任务数
     * @param maxSubSteps   sub_agent 最大步数
     * @return 提示词文本
     */
    private String buildPlannerPrompt(Set<String> toolWhitelist, int maxTasks, int maxSubSteps) {
        String toolList = toolWhitelist == null
                ? ""
                : toolWhitelist.stream().sorted().collect(Collectors.joining(", "));
        return promptService.parallelPlannerSystemPrompt(
                toolList,
                maxTasks,
                maxSubSteps,
                maxParallelTasks,
                maxSubAgents
        );
    }
}
