package world.willfrog.agent.workflow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.agent.service.AgentEventService;

import java.util.*;

/**
 * DAG 并行执行器（ReAct 模式）。
 *
 * <p>由于新的简化 Todo 格式下，数据依赖通过 ReAct 上下文传递，
 * DAG 模式的实现需要重新设计。当前版本暂时使用线性执行作为占位。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DagWorkflowExecutor implements WorkflowExecutor {

    private final AgentEventService eventService;
    private final LinearWorkflowExecutor linearWorkflowExecutor;

    @Override
    public WorkflowExecutionResult execute(WorkflowRequest request) {
        String runId = request.getRun().getId();
        String userId = request.getUserId();

        log.warn("DAG mode is currently using linear execution as placeholder. " +
                "Full DAG implementation with ReAct context sharing is TODO.");

        eventService.append(runId, userId, "DAG_EXECUTION_FALLBACK", Map.of(
                "message", "Using linear execution as DAG placeholder"
        ));

        // 暂时使用线性执行
        return linearWorkflowExecutor.execute(request);
    }
}
