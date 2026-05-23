package world.willfrog.agentlangchain.planning;

import dev.langchain4j.model.output.structured.Description;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Description("A linear todo plan for completing an agent run")
public class LangchainTodoPlanResponse {

    @Description("Concise reasoning for the plan. Do not include hidden chain-of-thought.")
    private String analysis;

    @Description("Ordered todo items. Keep the list at or below maxTodos.")
    private List<TodoItemResponse> items = new ArrayList<>();

    @Description("Financial entities, stock codes, fund codes, index names, or date ranges explicitly mentioned by the user.")
    private List<String> extractedEntities = new ArrayList<>();

    @Data
    public static class TodoItemResponse {

        @Description("Stable id such as todo_1")
        private String id;

        @Description("1-based execution order")
        private Integer sequence;

        @Description("One to three sentences describing what this todo should accomplish")
        private String description;

        @Description("Optional dependency todo ids. For P1 Linear-only this is normally empty.")
        private List<String> dependsOn = new ArrayList<>();

        @Description("Optional grouping key for future DAG execution")
        private String groupKey;

        @Description("Whether this task is safe to parallelize in a future DAG executor")
        private Boolean parallelizable;
    }
}
