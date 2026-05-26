package world.willfrog.agentlangchain.orchestration.dag;

import world.willfrog.agentlangchain.orchestration.LangchainCompletedTodo;
import world.willfrog.agentlangchain.orchestration.LangchainTodoNodeResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe completed todos + dataset refs shared across DAG workers.
 */
public class LangchainDagSharedContext {

    private final CopyOnWriteArrayList<LangchainCompletedTodo> completedTodos = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, String> datasetRefs = new ConcurrentHashMap<>();

    public List<LangchainCompletedTodo> completedTodosSnapshot() {
        return List.copyOf(completedTodos);
    }

    public Map<String, String> datasetRefsSnapshot() {
        return Map.copyOf(datasetRefs);
    }

    public void mergeDatasetRefs(Map<String, String> refs) {
        if (refs == null || refs.isEmpty()) {
            return;
        }
        datasetRefs.putAll(refs);
    }

    public void addCompletedTodo(LangchainCompletedTodo todo) {
        if (todo != null) {
            completedTodos.add(todo);
        }
    }

    public void recordNodeResult(LangchainTodoNodeResult result, Map<String, String> localDatasetRefs) {
        if (localDatasetRefs != null) {
            datasetRefs.putAll(localDatasetRefs);
        }
    }
}
