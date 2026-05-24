package world.willfrog.agentlangchain.orchestration.dag;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.service.AgentRunStateStore;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agent.workflow.TodoStatus;
import world.willfrog.agent.workflow.WorkflowState;
import world.willfrog.agentlangchain.orchestration.LangchainTodoNodeResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class LangchainDagStateRecorder {

    private final ObjectProvider<AgentRunStateStore> stateStoreProvider;

    public LangchainDagStateRecorder(ObjectProvider<AgentRunStateStore> stateStoreProvider) {
        this.stateStoreProvider = stateStoreProvider;
    }

    public void persistNodeState(String runId,
                                List<TodoItem> planItems,
                                Object lock,
                                Map<String, TodoItem> nodeStates,
                                TodoItem item,
                                TodoStatus status,
                                LangchainTodoNodeResult record,
                                int totalToolCallsUsed) {
        if (runId == null || runId.isBlank() || item == null || status == null) {
            return;
        }
        AgentRunStateStore stateStore = stateStoreProvider.getIfAvailable();
        if (stateStore == null) {
            return;
        }
        synchronized (lock) {
            nodeStates.put(item.getId(), copyWithStatus(item, status, record));
            List<TodoItem> completedItems = new ArrayList<>();
            Set<String> completedNodeIds = new HashSet<>();
            Set<String> runningNodeIds = new HashSet<>();
            for (TodoItem planItem : planItems) {
                TodoItem state = nodeStates.get(planItem.getId());
                if (state == null || state.getStatus() == null || state.getStatus() == TodoStatus.PENDING) {
                    continue;
                }
                if (state.getStatus() == TodoStatus.RUNNING) {
                    runningNodeIds.add(state.getId());
                    continue;
                }
                completedItems.add(state);
                if (state.getStatus() == TodoStatus.COMPLETED) {
                    completedNodeIds.add(state.getId());
                }
            }
            stateStore.saveWorkflowState(runId, WorkflowState.builder()
                    .executionMode(PlanExecutionMode.DAG)
                    .completedItems(completedItems)
                    .completedNodeIds(completedNodeIds)
                    .runningNodeIds(runningNodeIds)
                    .toolCallsUsed(Math.max(0, totalToolCallsUsed))
                    .savedAt(Instant.now())
                    .build());
        }
    }

    private TodoItem copyWithStatus(TodoItem item, TodoStatus status, LangchainTodoNodeResult record) {
        return TodoItem.builder()
                .id(item.getId())
                .sequence(item.getSequence())
                .description(item.getDescription())
                .dependsOn(item.getDependsOn() == null ? List.of() : new ArrayList<>(item.getDependsOn()))
                .groupKey(item.getGroupKey())
                .parallelizable(item.isParallelizable())
                .status(status)
                .createdAt(item.getCreatedAt())
                .completedAt(isTerminal(status) ? Instant.now() : null)
                .resultSummary(record == null ? null : nvl(record.getSummary()))
                .output(record == null ? null : nvl(record.getOutput()))
                .build();
    }

    private boolean isTerminal(TodoStatus status) {
        return status == TodoStatus.COMPLETED
                || status == TodoStatus.FAILED
                || status == TodoStatus.SKIPPED;
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }
}
