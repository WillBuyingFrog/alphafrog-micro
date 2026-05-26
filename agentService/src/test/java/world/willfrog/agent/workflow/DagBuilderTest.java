package world.willfrog.agent.workflow;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DagBuilderTest {

    private final DagBuilder dagBuilder = new DagBuilder();

    @Test
    void build_emptyPlanReturnsEmptyGraph() {
        TodoPlan plan = new TodoPlan();
        ExecutionGraph graph = dagBuilder.build(plan);
        assertEquals(0, graph.getTotalNodes());
        assertEquals(0, graph.getMaxDepth());
        assertEquals(0, graph.getMaxParallelism());
    }

    @Test
    void build_nullPlanReturnsEmptyGraph() {
        ExecutionGraph graph = dagBuilder.build(null);
        assertEquals(0, graph.getTotalNodes());
    }

    @Test
    void build_linearChainProducesCorrectGraph() {
        // todo_1 -> todo_2 -> todo_3
        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder().id("todo_1").sequence(1).build());
        items.add(TodoItem.builder().id("todo_2").sequence(2).dependsOn(List.of("todo_1")).build());
        items.add(TodoItem.builder().id("todo_3").sequence(3).dependsOn(List.of("todo_2")).build());

        TodoPlan plan = TodoPlan.builder().items(items).build();
        ExecutionGraph graph = dagBuilder.build(plan);

        assertEquals(3, graph.getTotalNodes());
        assertEquals(3, graph.getMaxDepth());
        assertEquals(1, graph.getMaxParallelism());

        // 拓扑排序应该是 todo_1, todo_2, todo_3
        assertEquals(List.of("todo_1", "todo_2", "todo_3"), graph.getTopologicalOrder());

        // 入度检查
        assertEquals(0, graph.getInDegree().get("todo_1"));
        assertEquals(1, graph.getInDegree().get("todo_2"));
        assertEquals(1, graph.getInDegree().get("todo_3"));
    }

    @Test
    void build_parallelNodesProducesCorrectGraph() {
        // todo_1 和 todo_2 无依赖（并行）-> todo_3 依赖两者
        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder().id("todo_1").sequence(1).build());
        items.add(TodoItem.builder().id("todo_2").sequence(2).build());
        items.add(TodoItem.builder().id("todo_3").sequence(3).dependsOn(List.of("todo_1", "todo_2")).build());

        TodoPlan plan = TodoPlan.builder().items(items).build();
        ExecutionGraph graph = dagBuilder.build(plan);

        assertEquals(3, graph.getTotalNodes());
        assertEquals(2, graph.getMaxDepth());
        assertEquals(2, graph.getMaxParallelism());

        // 入度检查
        assertEquals(0, graph.getInDegree().get("todo_1"));
        assertEquals(0, graph.getInDegree().get("todo_2"));
        assertEquals(2, graph.getInDegree().get("todo_3"));
    }

    @Test
    void build_diamondDagProducesCorrectGraph() {
        // todo_1 -> todo_2, todo_3; todo_2, todo_3 -> todo_4
        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder().id("todo_1").sequence(1).build());
        items.add(TodoItem.builder().id("todo_2").sequence(2).dependsOn(List.of("todo_1")).build());
        items.add(TodoItem.builder().id("todo_3").sequence(3).dependsOn(List.of("todo_1")).build());
        items.add(TodoItem.builder().id("todo_4").sequence(4).dependsOn(List.of("todo_2", "todo_3")).build());

        TodoPlan plan = TodoPlan.builder().items(items).build();
        ExecutionGraph graph = dagBuilder.build(plan);

        assertEquals(4, graph.getTotalNodes());
        assertEquals(3, graph.getMaxDepth());
        assertEquals(2, graph.getMaxParallelism());
    }

    @Test
    void build_circularDependencyThrowsException() {
        // todo_1 -> todo_2 -> todo_1 (循环)
        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder().id("todo_1").sequence(1).dependsOn(List.of("todo_2")).build());
        items.add(TodoItem.builder().id("todo_2").sequence(2).dependsOn(List.of("todo_1")).build());

        TodoPlan plan = TodoPlan.builder().items(items).build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> dagBuilder.build(plan));
        assertTrue(ex.getMessage().contains("Circular dependency"));
    }

    @Test
    void build_unknownDependencyIsIgnored() {
        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder().id("todo_1").sequence(1).dependsOn(List.of("non_existent")).build());

        TodoPlan plan = TodoPlan.builder().items(items).build();
        ExecutionGraph graph = dagBuilder.build(plan);

        assertEquals(1, graph.getTotalNodes());
        assertEquals(0, graph.getInDegree().get("todo_1"));
    }

    @Test
    void build_readyNodesReturnsCorrectSet() {
        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder().id("todo_1").sequence(1).build());
        items.add(TodoItem.builder().id("todo_2").sequence(2).build());
        items.add(TodoItem.builder().id("todo_3").sequence(3).dependsOn(List.of("todo_1")).build());

        TodoPlan plan = TodoPlan.builder().items(items).build();
        ExecutionGraph graph = dagBuilder.build(plan);

        List<String> ready = graph.getReadyNodes();
        assertEquals(2, ready.size());
        assertTrue(ready.contains("todo_1"));
        assertTrue(ready.contains("todo_2"));
    }

    @Test
    void build_allIndependentTasksAreParallel() {
        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder().id("todo_1").sequence(1).build());
        items.add(TodoItem.builder().id("todo_2").sequence(2).build());
        items.add(TodoItem.builder().id("todo_3").sequence(3).build());

        TodoPlan plan = TodoPlan.builder().items(items).build();
        ExecutionGraph graph = dagBuilder.build(plan);

        assertEquals(3, graph.getTotalNodes());
        assertEquals(1, graph.getMaxDepth());
        assertEquals(3, graph.getMaxParallelism());
        assertEquals(3, graph.getReadyNodes().size());
    }

    @Test
    void build_successorsReturnCorrectList() {
        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder().id("todo_1").sequence(1).build());
        items.add(TodoItem.builder().id("todo_2").sequence(2).dependsOn(List.of("todo_1")).build());
        items.add(TodoItem.builder().id("todo_3").sequence(3).dependsOn(List.of("todo_1")).build());

        TodoPlan plan = TodoPlan.builder().items(items).build();
        ExecutionGraph graph = dagBuilder.build(plan);

        List<String> successors = graph.getSuccessors("todo_1");
        assertEquals(2, successors.size());
        assertTrue(successors.contains("todo_2"));
        assertTrue(successors.contains("todo_3"));
    }

    @Test
    void build_singleNodeGraph() {
        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder().id("todo_1").sequence(1).build());

        TodoPlan plan = TodoPlan.builder().items(items).build();
        ExecutionGraph graph = dagBuilder.build(plan);

        assertEquals(1, graph.getTotalNodes());
        assertEquals(1, graph.getMaxDepth());
        assertEquals(1, graph.getMaxParallelism());
        assertEquals(1, graph.getReadyNodes().size());
        assertNotNull(graph.getNode("todo_1"));
    }
}
