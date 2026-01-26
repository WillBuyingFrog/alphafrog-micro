package world.willfrog.agent.graph;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class ParallelPlan {
    private String strategy;
    private String finalHint;
    private List<PlanTask> tasks = new ArrayList<>();

    @Data
    public static class PlanTask {
        private String id;
        private String type;
        private String tool;
        private Map<String, Object> args;
        private List<String> dependsOn = new ArrayList<>();
        private String goal;
        private Integer maxSteps;
        private String description;
    }
}
