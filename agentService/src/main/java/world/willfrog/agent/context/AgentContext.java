package world.willfrog.agent.context;

public class AgentContext {
    private static final ThreadLocal<String> RUN_ID_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<String> USER_ID_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<String> PHASE_HOLDER = new ThreadLocal<>();

    public static void setRunId(String runId) {
        RUN_ID_HOLDER.set(runId);
    }

    public static String getRunId() {
        return RUN_ID_HOLDER.get();
    }

    public static void setUserId(String userId) {
        USER_ID_HOLDER.set(userId);
    }

    public static String getUserId() {
        return USER_ID_HOLDER.get();
    }

    public static void setPhase(String phase) {
        PHASE_HOLDER.set(phase);
    }

    public static String getPhase() {
        return PHASE_HOLDER.get();
    }

    public static void clearPhase() {
        PHASE_HOLDER.remove();
    }

    public static void clear() {
        RUN_ID_HOLDER.remove();
        USER_ID_HOLDER.remove();
        clearPhase();
    }
}
