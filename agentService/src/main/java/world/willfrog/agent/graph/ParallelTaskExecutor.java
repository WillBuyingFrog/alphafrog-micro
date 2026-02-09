package world.willfrog.agent.graph;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.agent.context.AgentContext;
import world.willfrog.agent.service.AgentObservabilityService;
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
/**
 * 并行任务执行器。
 * <p>
 * 职责：
 * 1. 依据 dependsOn 做批次调度；
 * 2. 将同一批 ready 任务投递到线程池并发执行；
 * 3. 持续写入任务级事件与状态，供前端/调试脚本观测。
 */
public class ParallelTaskExecutor {

    /** 工具调用统一路由。 */
    private final ToolRouter toolRouter;
    /** 子代理执行器。 */
    private final SubAgentRunner subAgentRunner;
    /** 事件写入服务。 */
    private final AgentEventService eventService;
    /** run 的计划/任务状态缓存。 */
    private final AgentRunStateStore stateStore;
    /** 并行执行线程池。 */
    private final ExecutorService parallelExecutor;

    /**
     * 执行并行计划任务。
     *
     * @param plan             并行计划
     * @param runId            任务 ID
     * @param userId           用户 ID
     * @param toolWhitelist    工具白名单
     * @param subAgentMaxSteps 子代理最大步数
     * @param context          全局上下文
     * @param model            聊天模型
     * @param existingResults  已有任务结果（断点续跑场景）
     * @return 全量任务结果映射
     */
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

        // 逐批执行：每轮只并发执行“依赖已经满足”的 ready 任务。
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

            // 当前批次任务并发执行，批次内通过 allOf 等待结束。
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (ParallelPlan.PlanTask task : ready) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        AgentContext.setRunId(runId);
                        AgentContext.setUserId(userId);
                        AgentContext.setPhase(AgentObservabilityService.PHASE_PARALLEL_EXECUTION);
                        Map<String, Object> startPayload = new java.util.HashMap<>();
                        startPayload.put("task_id", task.getId());
                        startPayload.put("type", nvl(task.getType()));
                        startPayload.put("tool", nvl(task.getTool()));
                        eventService.append(runId, userId, "PARALLEL_TASK_STARTED", startPayload);

                        stateStore.markTaskStarted(runId, task);
                        String taskContext = buildContext(task, context, results);
                        ParallelTaskResult result = executeTask(task, runId, userId, toolWhitelist, subAgentMaxSteps, taskContext, model);
                        results.put(task.getId(), result);
                        stateStore.saveTaskResult(runId, task.getId(), result);
                        Map<String, Object> finishPayload = new java.util.HashMap<>();
                        finishPayload.put("task_id", task.getId());
                        finishPayload.put("success", result.isSuccess());
                        finishPayload.put("output_preview", preview(result.getOutput()));
                        finishPayload.put("cache", result.getCache() == null ? Map.of() : result.getCache());
                        eventService.append(runId, userId, "PARALLEL_TASK_FINISHED", finishPayload);
                    } catch (Exception e) {
                        // 线程内异常不再外抛，统一转换成失败任务并记录内部错误事件。
                        log.warn("Parallel task execution failed: runId={}, taskId={}", runId, task.getId(), e);
                        ParallelTaskResult failed = ParallelTaskResult.builder()
                                .taskId(task.getId())
                                .type(nvl(task.getType()))
                                .success(false)
                                .error(e.getMessage())
                                .build();
                        results.put(task.getId(), failed);
                        stateStore.saveTaskResult(runId, task.getId(), failed);
                        eventService.append(runId, userId, "PARALLEL_TASK_FAILED_INTERNAL", Map.of(
                                "task_id", task.getId(),
                                "error", e.getClass().getSimpleName() + ": " + nvl(e.getMessage())
                        ));
                    } finally {
                        AgentContext.clear();
                    }
                }, parallelExecutor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            pending.removeAll(ready);
        }
        return results;
    }

    /**
     * 执行单个任务。
     *
     * @param task             任务定义
     * @param runId            任务 ID
     * @param userId           用户 ID
     * @param toolWhitelist    工具白名单
     * @param subAgentMaxSteps 子代理最大步数
     * @param context          任务上下文
     * @param model            聊天模型
     * @return 单任务结果
     */
    private ParallelTaskResult executeTask(ParallelPlan.PlanTask task,
                                           String runId,
                                           String userId,
                                           Set<String> toolWhitelist,
                                           int subAgentMaxSteps,
                                           String context,
                                           dev.langchain4j.model.chat.ChatLanguageModel model) {
        String type = task.getType() == null ? "" : task.getType().trim().toLowerCase();
        if (type.equals("tool")) {
            ToolRouter.ToolInvocationResult invokeResult = toolRouter.invokeWithMeta(task.getTool(), safeArgs(task.getArgs()));
            String output = invokeResult.getOutput();
            return ParallelTaskResult.builder()
                    .taskId(task.getId())
                    .type("tool")
                    .success(invokeResult.isSuccess())
                    .output(output)
                    .cache(toolRouter.toEventCachePayload(invokeResult))
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
                    .cache(toolRouter.toEventCachePayload(null))
                    .build();
        }
        return ParallelTaskResult.builder()
                .taskId(task.getId())
                .type(type)
                .success(false)
                .error("unsupported task type")
                .cache(toolRouter.toEventCachePayload(null))
                .build();
    }

    /**
     * 判断任务依赖是否全部满足。
     *
     * @param task 当前任务
     * @param done 已完成任务 ID 集合
     * @return true 表示可执行
     */
    private boolean depsSatisfied(ParallelPlan.PlanTask task, Set<String> done) {
        List<String> deps = task.getDependsOn();
        if (deps == null || deps.isEmpty()) {
            return true;
        }
        return done.containsAll(deps);
    }

    /**
     * 参数空安全处理。
     *
     * @param args 原始参数
     * @return 非空参数 map
     */
    private Map<String, Object> safeArgs(Map<String, Object> args) {
        return args == null ? Collections.emptyMap() : args;
    }

    /**
     * 为有依赖的任务构建上下文，附加依赖任务结果摘要。
     *
     * @param task       当前任务
     * @param baseContext 全局上下文
     * @param results    当前已产出的任务结果
     * @return 拼接后的上下文字符串
     */
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

    /**
     * 文本预览裁剪，避免事件 payload 过大。
     *
     * @param text 原始文本
     * @return 预览文本
     */
    private String preview(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() > 300) {
            return text.substring(0, 300);
        }
        return text;
    }

    /**
     * 空值转空字符串，便于事件 payload 处理。
     *
     * @param text 原始文本
     * @return 非空文本
     */
    private String nvl(String text) {
        return text == null ? "" : text;
    }
}
