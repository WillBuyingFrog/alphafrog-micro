package world.willfrog.agent.workflow;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class PlanPatcher {

    @SuppressWarnings("unchecked")
    public TodoPlan applyPatch(TodoPlan original, PlanPatch patch) {
        if (original == null || patch == null || patch.getPatchType() == null) {
            return original;
        }
        return switch (patch.getPatchType()) {
            case INSERT -> insertTodo(original, patch);
            case DELETE -> deleteTodo(original, patch);
            case REPLACE -> replaceTodo(original, patch);
            case ADD_DEPENDENCY, MARK_PARALLEL -> {
                log.debug("Patch type {} is deferred to DAG workflow, keep plan unchanged", patch.getPatchType());
                yield original;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private TodoPlan insertTodo(TodoPlan original, PlanPatch patch) {
        Map<String, Object> patchData = patch.getPatchData();
        if (patchData == null || !patchData.containsKey("newTodo")) {
            log.warn("INSERT patch missing newTodo data, skipping");
            return original;
        }

        Object newTodoObj = patchData.get("newTodo");
        if (!(newTodoObj instanceof Map<?, ?> newTodoMap)) {
            log.warn("INSERT patch newTodo is not a map, skipping");
            return original;
        }

        String id = nvl((String) newTodoMap.get("id"));
        if (id.isBlank()) {
            id = "todo_patch_" + java.util.UUID.randomUUID().toString().substring(0, 8);
        }

        String toolName = nvl((String) newTodoMap.get("toolName"));
        String reasoning = nvl((String) newTodoMap.get("reasoning"));
        String typeStr = nvl((String) newTodoMap.get("type"));
        TodoType type = parseTodoType(typeStr);

        Map<String, Object> params = new LinkedHashMap<>();
        Object paramsObj = newTodoMap.get("params");
        if (paramsObj instanceof Map<?, ?> paramsMap) {
            for (Map.Entry<?, ?> entry : paramsMap.entrySet()) {
                params.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }

        TodoItem newItem = TodoItem.builder()
                .id(id)
                .type(type)
                .toolName(toolName)
                .params(params)
                .reasoning(reasoning)
                .status(TodoStatus.PENDING)
                .createdAt(Instant.now())
                .build();

        List<TodoItem> items = new ArrayList<>(original.getItems());
        String targetId = patch.getTargetTodoId();
        int insertIndex = items.size();
        if (targetId != null && !targetId.isBlank()) {
            for (int i = 0; i < items.size(); i++) {
                if (targetId.equals(items.get(i).getId())) {
                    insertIndex = i + 1;
                    break;
                }
            }
        }
        items.add(insertIndex, newItem);
        resequence(items);

        TodoPlan patched = new TodoPlan();
        patched.setAnalysis(original.getAnalysis());
        patched.setItems(items);
        return patched;
    }

    private TodoPlan deleteTodo(TodoPlan original, PlanPatch patch) {
        String targetId = patch.getTargetTodoId();
        if (targetId == null || targetId.isBlank()) {
            log.warn("DELETE patch missing targetTodoId, skipping");
            return original;
        }

        List<TodoItem> items = new ArrayList<>();
        for (TodoItem item : original.getItems()) {
            if (!targetId.equals(item.getId())) {
                items.add(item);
            }
        }
        resequence(items);

        TodoPlan patched = new TodoPlan();
        patched.setAnalysis(original.getAnalysis());
        patched.setItems(items);
        return patched;
    }

    @SuppressWarnings("unchecked")
    private TodoPlan replaceTodo(TodoPlan original, PlanPatch patch) {
        String targetId = patch.getTargetTodoId();
        if (targetId == null || targetId.isBlank()) {
            log.warn("REPLACE patch missing targetTodoId, skipping");
            return original;
        }

        Map<String, Object> patchData = patch.getPatchData();
        if (patchData == null) {
            return original;
        }

        Object newParamsObj = patchData.get("newParams");
        Map<String, Object> newParams = null;
        if (newParamsObj instanceof Map<?, ?> paramsMap) {
            newParams = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : paramsMap.entrySet()) {
                newParams.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }

        List<TodoItem> items = new ArrayList<>();
        for (TodoItem item : original.getItems()) {
            if (targetId.equals(item.getId())) {
                TodoItem replaced = TodoItem.builder()
                        .id(item.getId())
                        .sequence(item.getSequence())
                        .type(item.getType())
                        .toolName(patchData.containsKey("toolName") ? nvl((String) patchData.get("toolName")) : item.getToolName())
                        .params(newParams != null ? newParams : item.getParams())
                        .reasoning(patchData.containsKey("reasoning") ? nvl((String) patchData.get("reasoning")) : item.getReasoning())
                        .executionMode(item.getExecutionMode())
                        .status(TodoStatus.PENDING)
                        .createdAt(item.getCreatedAt())
                        .build();
                items.add(replaced);
            } else {
                items.add(item);
            }
        }

        TodoPlan patched = new TodoPlan();
        patched.setAnalysis(original.getAnalysis());
        patched.setItems(items);
        return patched;
    }

    private void resequence(List<TodoItem> items) {
        for (int i = 0; i < items.size(); i++) {
            items.get(i).setSequence(i + 1);
        }
    }

    private TodoType parseTodoType(String typeStr) {
        if (typeStr == null || typeStr.isBlank()) {
            return TodoType.TOOL_CALL;
        }
        try {
            return TodoType.valueOf(typeStr.trim().toUpperCase());
        } catch (Exception e) {
            return TodoType.TOOL_CALL;
        }
    }

    private String nvl(String text) {
        return text == null ? "" : text;
    }
}
