package world.willfrog.agent.context;

public class AgentContext {
    private static final ThreadLocal<String> RUN_ID_HOLDER = new ThreadLocal<>();

    public static void setRunId(String runId) {
        RUN_ID_HOLDER.set(runId);
    }

    public static String getRunId() {
        return RUN_ID_HOLDER.get();
    }

    public static void clear() {
        RUN_ID_HOLDER.remove();
    }
}
