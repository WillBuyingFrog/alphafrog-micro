package world.willfrog.agent.workflow;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExecutionModeSelectorTest {

    private final ExecutionModeSelector selector = new ExecutionModeSelector();

    @Test
    void select_nullPlanReturnsLinear() {
        assertEquals(PlanExecutionMode.LINEAR, selector.select(null));
    }

    @Test
    void select_emptyPlanReturnsLinear() {
        TodoPlan plan = new TodoPlan();
        assertEquals(PlanExecutionMode.LINEAR, selector.select(plan));
    }

    @Test
    void select_explicitLinearModeReturnsLinear() {
        TodoPlan plan = TodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(TodoItem.builder().id("todo_1").build()))
                .build();
        assertEquals(PlanExecutionMode.LINEAR, selector.select(plan));
    }

    @Test
    void select_explicitDagModeReturnsDag() {
        TodoPlan plan = TodoPlan.builder()
                .executionMode(PlanExecutionMode.DAG)
                .items(List.of(TodoItem.builder().id("todo_1").build()))
                .build();
        assertEquals(PlanExecutionMode.DAG, selector.select(plan));
    }

    @Test
    void select_autoWithDependenciesReturnsDag() {
        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder().id("todo_1").build());
        items.add(TodoItem.builder().id("todo_2").dependsOn(List.of("todo_1")).build());

        TodoPlan plan = TodoPlan.builder()
                .executionMode(PlanExecutionMode.AUTO)
                .items(items)
                .build();
        assertEquals(PlanExecutionMode.DAG, selector.select(plan));
    }

    @Test
    void select_autoWithParallelizableReturnsDag() {
        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder().id("todo_1").parallelizable(true).build());
        items.add(TodoItem.builder().id("todo_2").build());

        TodoPlan plan = TodoPlan.builder()
                .executionMode(PlanExecutionMode.AUTO)
                .items(items)
                .build();
        assertEquals(PlanExecutionMode.DAG, selector.select(plan));
    }

    @Test
    void select_autoWithThreeOrMoreIndependentTasksReturnsLinear() {
        // After refactoring: independent tasks without explicit dependencies
        // no longer automatically trigger DAG mode. DAG requires explicit API parameter.
        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder().id("todo_1").build());
        items.add(TodoItem.builder().id("todo_2").build());
        items.add(TodoItem.builder().id("todo_3").build());

        TodoPlan plan = TodoPlan.builder()
                .executionMode(PlanExecutionMode.AUTO)
                .items(items)
                .build();
        assertEquals(PlanExecutionMode.LINEAR, selector.select(plan));
    }

    @Test
    void select_autoWithTwoSimpleTasksReturnsLinear() {
        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder().id("todo_1").build());
        items.add(TodoItem.builder().id("todo_2").build());

        TodoPlan plan = TodoPlan.builder()
                .executionMode(PlanExecutionMode.AUTO)
                .items(items)
                .build();
        assertEquals(PlanExecutionMode.LINEAR, selector.select(plan));
    }

    @Test
    void select_nullExecutionModeUsesAutoStrategy() {
        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder().id("todo_1").build());
        items.add(TodoItem.builder().id("todo_2").dependsOn(List.of("todo_1")).build());

        TodoPlan plan = TodoPlan.builder().items(items).build();
        assertEquals(PlanExecutionMode.DAG, selector.select(plan));
    }

    @Test
    void selectByVoting_emptyJudgesUsesDefault() {
        TodoPlan plan = TodoPlan.builder()
                .items(List.of(TodoItem.builder().id("todo_1").build()))
                .build();
        PlanExecutionMode result = selector.selectByVoting(plan, List.of(), Map.of());
        assertEquals(PlanExecutionMode.LINEAR, result);
    }

    @Test
    void selectByVoting_majorityDagWins() {
        TodoPlan plan = TodoPlan.builder()
                .items(List.of(TodoItem.builder().id("todo_1").build()))
                .build();

        List<ExecutionModeSelector.ExecutionModeJudge> judges = List.of(
                new ExecutionModeSelector.ExecutionModeJudge() {
                    @Override
                    public String getName() { return "judge1"; }
                    @Override
                    public PlanExecutionMode vote(TodoPlan p, Map<String, Object> ctx) {
                        return PlanExecutionMode.DAG;
                    }
                },
                new ExecutionModeSelector.ExecutionModeJudge() {
                    @Override
                    public String getName() { return "judge2"; }
                    @Override
                    public PlanExecutionMode vote(TodoPlan p, Map<String, Object> ctx) {
                        return PlanExecutionMode.DAG;
                    }
                },
                new ExecutionModeSelector.ExecutionModeJudge() {
                    @Override
                    public String getName() { return "judge3"; }
                    @Override
                    public PlanExecutionMode vote(TodoPlan p, Map<String, Object> ctx) {
                        return PlanExecutionMode.LINEAR;
                    }
                }
        );

        PlanExecutionMode result = selector.selectByVoting(plan, judges, Map.of());
        assertEquals(PlanExecutionMode.DAG, result);
    }

    @Test
    void selectByVoting_majorityLinearWins() {
        TodoPlan plan = TodoPlan.builder()
                .items(List.of(TodoItem.builder().id("todo_1").build()))
                .build();

        List<ExecutionModeSelector.ExecutionModeJudge> judges = List.of(
                new ExecutionModeSelector.ExecutionModeJudge() {
                    @Override
                    public String getName() { return "judge1"; }
                    @Override
                    public PlanExecutionMode vote(TodoPlan p, Map<String, Object> ctx) {
                        return PlanExecutionMode.LINEAR;
                    }
                },
                new ExecutionModeSelector.ExecutionModeJudge() {
                    @Override
                    public String getName() { return "judge2"; }
                    @Override
                    public PlanExecutionMode vote(TodoPlan p, Map<String, Object> ctx) {
                        return PlanExecutionMode.LINEAR;
                    }
                }
        );

        PlanExecutionMode result = selector.selectByVoting(plan, judges, Map.of());
        assertEquals(PlanExecutionMode.LINEAR, result);
    }

    @Test
    void selectByVoting_failingJudgeIsIgnored() {
        TodoPlan plan = TodoPlan.builder()
                .items(List.of(TodoItem.builder().id("todo_1").build()))
                .build();

        List<ExecutionModeSelector.ExecutionModeJudge> judges = List.of(
                new ExecutionModeSelector.ExecutionModeJudge() {
                    @Override
                    public String getName() { return "crasher"; }
                    @Override
                    public PlanExecutionMode vote(TodoPlan p, Map<String, Object> ctx) {
                        throw new RuntimeException("boom");
                    }
                },
                new ExecutionModeSelector.ExecutionModeJudge() {
                    @Override
                    public String getName() { return "dag_voter"; }
                    @Override
                    public PlanExecutionMode vote(TodoPlan p, Map<String, Object> ctx) {
                        return PlanExecutionMode.DAG;
                    }
                }
        );

        PlanExecutionMode result = selector.selectByVoting(plan, judges, Map.of());
        assertEquals(PlanExecutionMode.DAG, result);
    }
}
