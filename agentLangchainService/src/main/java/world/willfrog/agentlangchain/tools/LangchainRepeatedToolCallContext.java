package world.willfrog.agentlangchain.tools;

import world.willfrog.agent.platform.context.AgentContext;

import java.util.LinkedHashMap;
import java.util.Map;

public final class LangchainRepeatedToolCallContext {

    private static final ThreadLocal<State> STATE = new ThreadLocal<>();

    private LangchainRepeatedToolCallContext() {
    }

    public static void start(String scopeKey) {
        STATE.set(new State(scopeKey));
    }

    static State currentOrCreate() {
        String scopeKey = currentScopeKey();
        State state = STATE.get();
        if (state == null || !state.scopeKey().equals(scopeKey)) {
            state = new State(scopeKey);
            STATE.set(state);
        }
        return state;
    }

    public static void clear() {
        STATE.remove();
    }

    private static String currentScopeKey() {
        String runId = nvl(AgentContext.getRunId());
        String todoId = nvl(AgentContext.getTodoId());
        String stage = nvl(AgentContext.getStage());
        String phase = nvl(AgentContext.getPhase());
        String key = runId + "|" + todoId + "|" + phase + "|" + stage;
        return key.isBlank() || "|||".equals(key) ? "default-thread-" + Thread.currentThread().getId() : key;
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    static final class State {
        private final String scopeKey;
        private final Map<LangchainToolCallSignature, Integer> counts = new LinkedHashMap<>();

        State(String scopeKey) {
            this.scopeKey = scopeKey == null ? "" : scopeKey;
        }

        String scopeKey() {
            return scopeKey;
        }

        int increment(LangchainToolCallSignature signature) {
            int count = counts.getOrDefault(signature, 0) + 1;
            counts.put(signature, count);
            return count;
        }
    }
}
