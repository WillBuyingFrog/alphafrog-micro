package world.willfrog.agent.workflow;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Plan 补丁应用器 - ReAct 模式简化版。
 *
 * <p>由于 Todo 只包含 description，补丁主要用于插入新的 Todo 或删除失败的 Todo，
 * 而不是修改具体的 params。</p>
 */
@Component
@Slf4j
public class PlanPatcher {

    public TodoPlan applyPatch(TodoPlan original, PlanPatch patch) {
        if (original == null || patch == null || patch.getPatchType() == null) {
            return original;
        }
        return switch (patch.getPatchType()) {
            case INSERT -> insertTodo(original, patch);
            case DELETE -> deleteTodo(original, patch);
            case REPLACE -> replaceTodoDescription(original, patch);
            case ADD_DEPENDENCY, MARK_PARALLEL -> {
                log.debug("Patch type {} is deferred to DAG workflow", patch.getPatchType());
                yield original;
            }
        };
    }

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

        String description = nvl((String) newTodoMap.get("description"));

        TodoItem newItem = TodoItem.builder()
                .id(id)
                .description(description)
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

    private TodoPlan replaceTodoDescription(TodoPlan original, PlanPatch patch) {
        String targetId = patch.getTargetTodoId();
        if (targetId == null || targetId.isBlank()) {
            log.warn("REPLACE patch missing targetTodoId, skipping");
            return original;
        }

        Map<String, Object> patchData = patch.getPatchData();
        if (patchData == null) {
            return original;
        }

        String newDescription = patchData.containsKey("newDescription")
                ? nvl((String) patchData.get("newDescription"))
                : null;

        List<TodoItem> items = new ArrayList<>();
        for (TodoItem item : original.getItems()) {
            if (targetId.equals(item.getId())) {
                TodoItem replaced = TodoItem.builder()
                        .id(item.getId())
                        .sequence(item.getSequence())
                        .description(newDescription != null ? newDescription : item.getDescription())
                        .dependsOn(item.getDependsOn())
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

    private String nvl(String text) {
        return text == null ? "" : text;
    }
}
