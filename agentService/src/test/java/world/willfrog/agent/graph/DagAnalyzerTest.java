package world.willfrog.agent.graph;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DagAnalyzerTest {

    private final DagAnalyzer analyzer = new DagAnalyzer();

    @Test
    void analyze_linearPlan_shouldReturnSingleParallelism() {
        ParallelPlan plan = plan(
                task("t1", List.of()),
                task("t2", List.of("t1")),
                task("t3", List.of("t2"))
        );

        DagAnalyzer.DagMetrics metrics = analyzer.analyze(plan);

        assertEquals(3, metrics.getTaskCount());
        assertEquals(1, metrics.getMaxParallelism());
        assertEquals(3, metrics.getCriticalPathLength());
        assertFalse(metrics.isHasCycle());
    }

    @Test
    void analyze_forkPlan_shouldReturnParallelWidth() {
        ParallelPlan plan = plan(
                task("t1", List.of()),
                task("t2", List.of("t1")),
                task("t3", List.of("t1")),
                task("t4", List.of("t2", "t3"))
        );

        DagAnalyzer.DagMetrics metrics = analyzer.analyze(plan);

        assertEquals(4, metrics.getTaskCount());
        assertEquals(2, metrics.getMaxParallelism());
        assertEquals(3, metrics.getCriticalPathLength());
        assertFalse(metrics.isHasCycle());
    }

    @Test
    void analyze_cyclePlan_shouldMarkCycle() {
        ParallelPlan plan = plan(
                task("t1", List.of("t3")),
                task("t2", List.of("t1")),
                task("t3", List.of("t2"))
        );

        DagAnalyzer.DagMetrics metrics = analyzer.analyze(plan);

        assertEquals(3, metrics.getTaskCount());
        assertTrue(metrics.isHasCycle());
    }

    private ParallelPlan plan(ParallelPlan.PlanTask... tasks) {
        ParallelPlan plan = new ParallelPlan();
        plan.setTasks(List.of(tasks));
        return plan;
    }

    private ParallelPlan.PlanTask task(String id, List<String> deps) {
        ParallelPlan.PlanTask task = new ParallelPlan.PlanTask();
        task.setId(id);
        task.setType("tool");
        task.setTool("searchStock");
        task.setDependsOn(deps);
        return task;
    }
}
