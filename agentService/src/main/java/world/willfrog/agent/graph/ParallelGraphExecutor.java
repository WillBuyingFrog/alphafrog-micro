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
public class ParallelGraphExecutor {

    private final ParallelTaskExecutor taskExecutor;
    private final ParallelPlanValidator planValidator = new ParallelPlanValidator();
    private final AgentRunMapper runMapper;
    private final AgentEventService eventService;
    private final AgentRunStateStore stateStore;
    private final ObjectMapper objectMapper;

    @Value("${agent.flow.parallel.enabled:true}")
    private boolean enabled;

    @Value("${agent.flow.parallel.max-tasks:6}")
    private int maxTasks;

    @Value("${agent.flow.parallel.sub-agent-max-steps:6}")
    private int subAgentMaxSteps;

    @Value("${agent.flow.parallel.max-parallel-tasks:-1}")
    private int maxParallelTasks;

    @Value("${agent.flow.parallel.max-sub-agents:-1}")
    private int maxSubAgents;

    public boolean isEnabled() {
        return enabled;
    }

    public boolean execute(AgentRun run,
                           String userId,
                           String userGoal,
                           ChatLanguageModel model,
                           List<ToolSpecification> toolSpecifications) {
        if (!enabled) {
            return false;
        }

        try {
            Set<String> toolWhitelist = toolSpecifications.stream()
                    .map(ToolSpecification::name)
                    .collect(Collectors.toSet());

            var graph = buildGraph(run, userId, userGoal, model, toolWhitelist);
            Map<String, Object> initial = Map.of(
                    "user_goal", userGoal
            );

            ParallelGraphState finalState = null;
            for (var event : graph.stream(initial)) {
                finalState = event.state();
            }
            if (finalState == null) {
                return false;
            }
            boolean planValid = finalState.planValid().orElse(false);
            boolean allDone = finalState.allDone().orElse(false);
            boolean paused = finalState.paused().orElse(false);
            String finalAnswer = finalState.finalAnswer().orElse("");
            if (!planValid) {
                return false;
            }
            if (!allDone) {
                return paused;
            }
            if (finalAnswer.isBlank()) {
                return false;
            }

            Map<String, Object> snapshot = new HashMap<>();
            snapshot.put("user_goal", userGoal);
            snapshot.put("plan", finalState.planJson().orElse("{}"));
            snapshot.put("task_results", finalState.taskResults().orElse(Map.of()));
            snapshot.put("answer", finalAnswer);

            String snapshotJson = objectMapper.writeValueAsString(snapshot);
            runMapper.updateSnapshot(run.getId(), userId, AgentRunStatus.COMPLETED, snapshotJson, true, null);
            eventService.append(run.getId(), userId, "COMPLETED", Map.of("answer", finalAnswer));
            stateStore.markRunStatus(run.getId(), AgentRunStatus.COMPLETED.name());
            return true;
        } catch (Exception e) {
            log.warn("Parallel graph execution failed", e);
            return false;
        }
    }

    private CompiledGraph<ParallelGraphState> buildGraph(AgentRun run,
                                                         String userId,
                                                         String userGoal,
                                                         ChatLanguageModel model,
                                                         Set<String> toolWhitelist) {
        try {
            var stateGraph = new StateGraph<>(ParallelGraphState.SCHEMA, ParallelGraphState::new)
                    .addNode("plan", node_async(state -> planNode(run, userId, userGoal, model, toolWhitelist)))
                    .addNode("execute", node_async(state -> executeNode(run, userId, userGoal, model, toolWhitelist, state)))
                    .addNode("final", node_async(state -> finalNode(run, userId, userGoal, model, state)))
                    .addEdge(StateGraph.START, "plan")
                    .addEdge("plan", "execute")
                    .addEdge("execute", "final")
                    .addEdge("final", StateGraph.END);

            return stateGraph.compile();
        } catch (GraphStateException e) {
            throw new IllegalStateException("Failed to build LangGraph4j flow", e);
        }
    }

    private Map<String, Object> planNode(AgentRun run,
                                         String userId,
                                         String userGoal,
                                         ChatLanguageModel model,
                                         Set<String> toolWhitelist) {
        eventService.append(run.getId(), userId, "PLAN_STARTED", Map.of("run_id", run.getId()));

        String planText;
        String planJson;
        boolean usedStoredPlan = false;
        boolean override = stateStore.isPlanOverride(run.getId());
        Optional<String> stored = stateStore.loadPlan(run.getId());
        if (stored.isEmpty() && run.getPlanJson() != null && !run.getPlanJson().isBlank() && !"{}".equals(run.getPlanJson().trim())) {
            stored = Optional.of(run.getPlanJson());
        }

        if (stored.isPresent()) {
            planJson = stored.get();
            planText = planJson;
            usedStoredPlan = true;
        } else {
            String prompt = buildPlannerPrompt(toolWhitelist, maxTasks, subAgentMaxSteps);
            Response<AiMessage> response = model.generate(List.of(
                    new SystemMessage(prompt),
                    new UserMessage(userGoal)
            ));
            planText = response.content().text();
            planJson = extractJson(planText);
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

    private Map<String, Object> executeNode(AgentRun run,
                                            String userId,
                                            String userGoal,
                                            ChatLanguageModel model,
                                            Set<String> toolWhitelist,
                                            ParallelGraphState state) {
        boolean planValid = state.planValid().orElse(false);
        if (!planValid) {
            return Map.of();
        }
        ParallelPlan plan = parsePlan(state.planJson().orElse("{}"));
        Map<String, ParallelTaskResult> existing = stateStore.loadTaskResults(run.getId());
        Map<String, ParallelTaskResult> results = taskExecutor.execute(plan, run.getId(), userId, toolWhitelist, subAgentMaxSteps, userGoal, model, existing);
        boolean allDone = isAllDone(plan, results);
        boolean paused = !eventService.isRunnable(run.getId(), userId);
        Map<String, Object> update = new HashMap<>();
        update.put("task_results", results);
        update.put("all_done", allDone);
        update.put("paused", paused);
        return update;
    }

    private Map<String, Object> finalNode(AgentRun run,
                                          String userId,
                                          String userGoal,
                                          ChatLanguageModel model,
                                          ParallelGraphState state) {
        boolean planValid = state.planValid().orElse(false);
        boolean allDone = state.allDone().orElse(false);
        if (!planValid || !allDone) {
            return Map.of("final_answer", "");
        }
        Map<String, ParallelTaskResult> results = state.taskResults().orElse(Map.of());
        String resultJson = safeWrite(results);
        String prompt = "你是金融分析助手，请基于任务结果给出最终结论。";
        Response<AiMessage> response = model.generate(List.of(
                new SystemMessage(prompt),
                new UserMessage("目标: " + userGoal + "\n结果: " + resultJson)
        ));
        String answer = response.content().text();
        return Map.of("final_answer", answer);
    }

    private ParallelPlan parsePlan(String json) {
        try {
            return objectMapper.readValue(json, ParallelPlan.class);
        } catch (Exception e) {
            return new ParallelPlan();
        }
    }

    private String safeWrite(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

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

    private String buildPlannerPrompt(Set<String> toolWhitelist, int maxTasks, int maxSubSteps) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是并行任务规划器。只输出 JSON。");
        sb.append("必须输出格式：{\"strategy\":\"parallel\",\"tasks\":[...],\"finalHint\":\"...\"}。");
        sb.append("任务字段：id,type( tool | sub_agent ),tool,args,dependsOn,goal,maxSteps,description。");
        sb.append("请优先拆分为可并行的任务，但不要牺牲正确性。");
        sb.append("约束：任务数 <= ").append(maxTasks)
                .append(", sub_agent 步数 <= ").append(maxSubSteps).append("。");
        if (maxParallelTasks > 0) {
            sb.append("并行任务数 <= ").append(maxParallelTasks).append("。");
        }
        if (maxSubAgents > 0) {
            sb.append("sub_agent 任务数 <= ").append(maxSubAgents).append("。");
        }
        sb.append("仅允许使用以下工具：").append(String.join(", ", toolWhitelist)).append("。");
        sb.append("可并行的任务应设置为空依赖；有依赖的任务使用 dependsOn。");
        return sb.toString();
    }
}
