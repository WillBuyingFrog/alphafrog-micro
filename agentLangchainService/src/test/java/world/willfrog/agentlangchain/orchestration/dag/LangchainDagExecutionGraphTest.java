package world.willfrog.agentlangchain.orchestration.dag;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.workflow.TodoItem;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LangchainDagExecutionGraphTest {

    @Test
    void from_shouldDetectCycle() {
        LangchainDagExecutionGraph graph = LangchainDagExecutionGraph.from(List.of(
                TodoItem.builder().id("a").sequence(1).description("a").dependsOn(List.of("b")).build(),
                TodoItem.builder().id("b").sequence(2).description("b").dependsOn(List.of("a")).build()
        ));
        assertThat(graph.hasCycle()).isTrue();
    }

    @Test
    void from_shouldIgnoreUnknownDependsOn() {
        LangchainDagExecutionGraph graph = LangchainDagExecutionGraph.from(List.of(
                TodoItem.builder().id("a").sequence(1).description("a").dependsOn(List.of("missing")).build()
        ));
        assertThat(graph.hasCycle()).isFalse();
        assertThat(graph.getDependencies("a")).isEmpty();
    }
}
