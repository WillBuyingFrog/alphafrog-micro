package world.willfrog.agent.graph;

import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ParallelGraphState extends AgentState {

    public static final Map<String, Channel<?>> SCHEMA = Map.of(
            "user_goal", Channels.base((oldValue, newValue) -> newValue),
            "plan_json", Channels.base((oldValue, newValue) -> newValue),
            "plan_valid", Channels.base((oldValue, newValue) -> newValue),
            "task_results", Channels.base((oldValue, newValue) -> newValue),
            "all_done", Channels.base((oldValue, newValue) -> newValue),
            "paused", Channels.base((oldValue, newValue) -> newValue),
            "final_answer", Channels.base((oldValue, newValue) -> newValue),
            "events", Channels.appender(ArrayList::new)
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

    public Optional<Map<String, ParallelTaskResult>> taskResults() {
        return this.value("task_results");
    }

    public Optional<Boolean> allDone() {
        return this.value("all_done");
    }

    public Optional<Boolean> paused() {
        return this.value("paused");
    }

    public Optional<String> finalAnswer() {
        return this.value("final_answer");
    }

    public List<Object> events() {
        return this.<List<Object>>value("events").orElse(List.of());
    }
}
