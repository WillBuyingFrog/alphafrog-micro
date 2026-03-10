package world.willfrog.agent.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PlanPatcherTest {

    private PlanPatcher patcher;

    @BeforeEach
    void setUp() {
        patcher = new PlanPatcher();
    }

    @Test
    void applyPatch_insertAfterTarget() {
        TodoPlan plan = planWithTodos("todo_1", "todo_2");

        Map<String, Object> newTodo = new LinkedHashMap<>();
        newTodo.put("id", "todo_1_1");
        newTodo.put("description", "补充查询基金");

        PlanPatch patch = PlanPatch.builder()
                .patchType(PatchType.INSERT)
                .targetTodoId("todo_1")
                .patchData(Map.of("newTodo", newTodo))
                .reason("需要补充查询基金数据")
                .build();

        TodoPlan patched = patcher.applyPatch(plan, patch);

        assertNotNull(patched);
        assertEquals(3, patched.getItems().size());
        assertEquals("todo_1", patched.getItems().get(0).getId());
        assertEquals("todo_1_1", patched.getItems().get(1).getId());
        assertEquals("todo_2", patched.getItems().get(2).getId());
        assertEquals(1, patched.getItems().get(0).getSequence());
        assertEquals(2, patched.getItems().get(1).getSequence());
        assertEquals(3, patched.getItems().get(2).getSequence());
    }

    @Test
    void applyPatch_insertAtEndWhenNoTarget() {
        TodoPlan plan = planWithTodos("todo_1");

        Map<String, Object> newTodo = new LinkedHashMap<>();
        newTodo.put("id", "todo_2");
        newTodo.put("description", "追加步骤");

        PlanPatch patch = PlanPatch.builder()
                .patchType(PatchType.INSERT)
                .patchData(Map.of("newTodo", newTodo))
                .reason("追加步骤")
                .build();

        TodoPlan patched = patcher.applyPatch(plan, patch);

        assertNotNull(patched);
        assertEquals(2, patched.getItems().size());
        assertEquals("todo_2", patched.getItems().get(1).getId());
    }

    @Test
    void applyPatch_deleteTodo() {
        TodoPlan plan = planWithTodos("todo_1", "todo_2", "todo_3");

        PlanPatch patch = PlanPatch.builder()
                .patchType(PatchType.DELETE)
                .targetTodoId("todo_2")
                .reason("移除无效步骤")
                .build();

        TodoPlan patched = patcher.applyPatch(plan, patch);

        assertNotNull(patched);
        assertEquals(2, patched.getItems().size());
        assertEquals("todo_1", patched.getItems().get(0).getId());
        assertEquals("todo_3", patched.getItems().get(1).getId());
        assertEquals(1, patched.getItems().get(0).getSequence());
        assertEquals(2, patched.getItems().get(1).getSequence());
    }

    @Test
    void applyPatch_replaceTodoDescription() {
        TodoPlan plan = planWithTodos("todo_1");
        plan.getItems().get(0).setDescription("old description");

        PlanPatch patch = PlanPatch.builder()
                .patchType(PatchType.REPLACE)
                .targetTodoId("todo_1")
                .patchData(Map.of("newDescription", "new description"))
                .reason("修改任务描述")
                .build();

        TodoPlan patched = patcher.applyPatch(plan, patch);

        assertNotNull(patched);
        assertEquals(1, patched.getItems().size());
        assertEquals("todo_1", patched.getItems().get(0).getId());
        assertEquals("new description", patched.getItems().get(0).getDescription());
        assertEquals(TodoStatus.PENDING, patched.getItems().get(0).getStatus());
    }

    @Test
    void applyPatch_nullPatchReturnsOriginal() {
        TodoPlan plan = planWithTodos("todo_1");
        TodoPlan result = patcher.applyPatch(plan, null);
        assertEquals(plan, result);
    }

    @Test
    void applyPatch_nullPlanReturnsNull() {
        PlanPatch patch = PlanPatch.builder().patchType(PatchType.DELETE).targetTodoId("x").build();
        TodoPlan result = patcher.applyPatch(null, patch);
        assertEquals(null, result);
    }

    @Test
    void applyPatch_deleteNonExistentTodoNoChange() {
        TodoPlan plan = planWithTodos("todo_1", "todo_2");

        PlanPatch patch = PlanPatch.builder()
                .patchType(PatchType.DELETE)
                .targetTodoId("todo_999")
                .reason("删除不存在的步骤")
                .build();

        TodoPlan patched = patcher.applyPatch(plan, patch);

        assertNotNull(patched);
        assertEquals(2, patched.getItems().size());
    }

    @Test
    void applyPatch_addDependencyNoopInLinearMode() {
        TodoPlan plan = planWithTodos("todo_1", "todo_2");
        PlanPatch patch = PlanPatch.builder()
                .patchType(PatchType.ADD_DEPENDENCY)
                .targetTodoId("todo_2")
                .patchData(Map.of("dependsOn", java.util.List.of("todo_1")))
                .reason("由 DAG 工作流处理")
                .build();

        TodoPlan patched = patcher.applyPatch(plan, patch);
        assertEquals(plan, patched);
    }

    @Test
    void applyPatch_markParallelNoopInLinearMode() {
        TodoPlan plan = planWithTodos("todo_1", "todo_2");
        PlanPatch patch = PlanPatch.builder()
                .patchType(PatchType.MARK_PARALLEL)
                .targetTodoId("todo_2")
                .patchData(Map.of("parallelizable", true, "groupKey", "batch"))
                .reason("由 DAG 工作流处理")
                .build();

        TodoPlan patched = patcher.applyPatch(plan, patch);
        assertEquals(plan, patched);
    }

    private TodoPlan planWithTodos(String... ids) {
        TodoPlan plan = new TodoPlan();
        plan.setAnalysis("test analysis");
        int seq = 1;
        for (String id : ids) {
            plan.getItems().add(TodoItem.builder()
                    .id(id)
                    .sequence(seq++)
                    .description("test description")
                    .status(TodoStatus.PENDING)
                    .build());
        }
        return plan;
    }
}
