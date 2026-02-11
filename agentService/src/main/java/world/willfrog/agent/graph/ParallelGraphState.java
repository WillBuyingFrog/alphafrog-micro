package world.willfrog.agent.graph;

import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ParallelGraphState extends AgentState {

    public static final Map<String, Channel<?>> SCHEMA = Map.ofEntries(
            Map.entry("user_goal", Channels.base((oldValue, newValue) -> newValue)),
            Map.entry("plan_json", Channels.base((oldValue, newValue) -> newValue)),
            Map.entry("plan_valid", Channels.base((oldValue, newValue) -> newValue)),
            Map.entry("plan_score", Channels.base((oldValue, newValue) -> newValue)),
            Map.entry("judge_summary", Channels.base((oldValue, newValue) -> newValue)),
            Map.entry("task_results", Channels.base((oldValue, newValue) -> newValue)),
            Map.entry("all_done", Channels.base((oldValue, newValue) -> newValue)),
            Map.entry("paused", Channels.base((oldValue, newValue) -> newValue)),
            Map.entry("replan_count", Channels.base((oldValue, newValue) -> newValue)),
            Map.entry("execution_failed", Channels.base((oldValue, newValue) -> newValue)),
            Map.entry("failure_reason", Channels.base((oldValue, newValue) -> newValue)),
            Map.entry("unresolved_tasks", Channels.base((oldValue, newValue) -> newValue)),
            Map.entry("final_answer", Channels.base((oldValue, newValue) -> newValue)),
            Map.entry("events", Channels.appender(ArrayList::new))
    );

    public ParallelGraphState(Map<String, Object> initData) {
        super(initData);
    }

    public Optional<String> userGoal() {
        return this.value("user_goal");
    }

    public Optional<String> planJson() {
        return this.value("plan_json");
    }

    public Optional<Boolean> planValid() {
        return this.value("plan_valid");
    }

    public Optional<Double> planScore() {
        return this.value("plan_score");
    }

    public Optional<Map<String, Object>> judgeSummary() {
        return this.value("judge_summary");
    }

    public Optional<Map<String, ParallelTaskResult>> taskResults() {
        return this.value("task_results");
    }

    public Optional<Boolean> allDone() {
        return this.value("all_done");
    }

    public Optional<Boolean> paused() {
        return this.value("paused");
    }

    public Optional<Integer> replanCount() {
        return this.value("replan_count");
    }

    public Optional<Boolean> executionFailed() {
        return this.value("execution_failed");
    }

    public Optional<String> failureReason() {
        return this.value("failure_reason");
    }

    public Optional<List<String>> unresolvedTasks() {
        return this.value("unresolved_tasks");
    }

    public Optional<String> finalAnswer() {
        return this.value("final_answer");
    }

    public List<Object> events() {
        return this.<List<Object>>value("events").orElse(List.of());
    }
}
