package world.willfrog.agent.graph;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.agent.service.AgentRunStateStore;
import world.willfrog.agent.service.AgentEventService;
import world.willfrog.agent.tool.ToolRouter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class ParallelTaskExecutor {

    private final ToolRouter toolRouter;
    private final SubAgentRunner subAgentRunner;
    private final AgentEventService eventService;
    private final AgentRunStateStore stateStore;
    private final ExecutorService parallelExecutor;

    public Map<String, ParallelTaskResult> execute(ParallelPlan plan,
                                                   String runId,
                                                   String userId,
                                                   Set<String> toolWhitelist,
                                                   int subAgentMaxSteps,
                                                   String context,
                                                   dev.langchain4j.model.chat.ChatLanguageModel model,
                                                   Map<String, ParallelTaskResult> existingResults) {
        if (plan == null || plan.getTasks() == null) {
            return Map.of();
        }
        Map<String, ParallelTaskResult> results = new ConcurrentHashMap<>();
        if (existingResults != null && !existingResults.isEmpty()) {
            results.putAll(existingResults);
        }
        List<ParallelPlan.PlanTask> pending = plan.getTasks().stream()
                .filter(task -> !results.containsKey(task.getId()))
                .collect(Collectors.toCollection(ArrayList::new));

        while (!pending.isEmpty()) {
            if (!eventService.isRunnable(runId, userId)) {
                break;
            }
            List<ParallelPlan.PlanTask> ready = pending.stream()
                    .filter(task -> depsSatisfied(task, results.keySet()))
                    .collect(Collectors.toList());

            if (ready.isEmpty()) {
                eventService.append(runId, userId, "PARALLEL_EXECUTION_BLOCKED", Map.of(
                        "reason", "no_ready_tasks_possible_cycle",
                        "pending_task_ids", pending.stream().map(ParallelPlan.PlanTask::getId).collect(Collectors.toList()),
                        "finished_task_ids", new ArrayList<>(results.keySet())
                ));
                log.warn("No ready tasks found, possible cyclic deps.");
                break;
            }

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (ParallelPlan.PlanTask task : ready) {
                futures.add(CompletableFuture.runAsync(() -> {
                    eventService.append(runId, userId, "PARALLEL_TASK_STARTED", Map.of(
                            "task_id", task.getId(),
                            "type", task.getType(),
                            "tool", task.getTool()
                    ));
                    stateStore.markTaskStarted(runId, task);
                    String taskContext = buildContext(task, context, results);
                    ParallelTaskResult result = executeTask(task, runId, userId, toolWhitelist, subAgentMaxSteps, taskContext, model);
                    results.put(task.getId(), result);
                    stateStore.saveTaskResult(runId, task.getId(), result);
                    eventService.append(runId, userId, "PARALLEL_TASK_FINISHED", Map.of(
                            "task_id", task.getId(),
                            "success", result.isSuccess(),
                            "output_preview", preview(result.getOutput())
                    ));
                }, parallelExecutor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            pending.removeAll(ready);
        }
        return results;
    }

    private ParallelTaskResult executeTask(ParallelPlan.PlanTask task,
                                           String runId,
                                           String userId,
                                           Set<String> toolWhitelist,
                                           int subAgentMaxSteps,
                                           String context,
                                           dev.langchain4j.model.chat.ChatLanguageModel model) {
        String type = task.getType() == null ? "" : task.getType().trim().toLowerCase();
        if (type.equals("tool")) {
            String output = toolRouter.invoke(task.getTool(), safeArgs(task.getArgs()));
            return ParallelTaskResult.builder()
                    .taskId(task.getId())
                    .type("tool")
                    .success(!output.startsWith("Tool invocation error"))
                    .output(output)
                    .build();
        }
        if (type.equals("sub_agent")) {
            SubAgentRunner.SubAgentRequest req = SubAgentRunner.SubAgentRequest.builder()
                    .runId(runId)
                    .userId(userId)
                    .taskId(task.getId())
                    .goal(task.getGoal())
                    .context(context)
                    .toolWhitelist(toolWhitelist)
                    .maxSteps(task.getMaxSteps() != null ? Math.min(task.getMaxSteps(), subAgentMaxSteps) : subAgentMaxSteps)
                    .build();
            SubAgentRunner.SubAgentResult subResult = subAgentRunner.run(req, model);
            return ParallelTaskResult.builder()
                    .taskId(task.getId())
                    .type("sub_agent")
                    .success(subResult.isSuccess())
                    .output(subResult.getAnswer())
                    .error(subResult.getError())
                    .build();
        }
        return ParallelTaskResult.builder()
                .taskId(task.getId())
                .type(type)
                .success(false)
                .error("unsupported task type")
                .build();
    }

    private boolean depsSatisfied(ParallelPlan.PlanTask task, Set<String> done) {
        List<String> deps = task.getDependsOn();
        if (deps == null || deps.isEmpty()) {
            return true;
        }
        return done.containsAll(deps);
    }

    private Map<String, Object> safeArgs(Map<String, Object> args) {
        return args == null ? Collections.emptyMap() : args;
    }

    private String buildContext(ParallelPlan.PlanTask task, String baseContext, Map<String, ParallelTaskResult> results) {
        if (task.getDependsOn() == null || task.getDependsOn().isEmpty()) {
            return baseContext;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(baseContext == null ? "" : baseContext);
        sb.append("\n依赖结果:\n");
        for (String dep : task.getDependsOn()) {
            ParallelTaskResult result = results.get(dep);
            if (result == null) {
                continue;
            }
            sb.append(dep).append(": ")
              .append(result.isSuccess() ? "ok" : "failed")
              .append(" -> ")
              .append(preview(result.getOutput()))
              .append("\n");
        }
        return sb.toString();
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() > 300) {
            return text.substring(0, 300);
        }
        return text;
    }
}
