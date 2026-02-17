package world.willfrog.agent.context;

public class AgentContext {
    private static final ThreadLocal<String> RUN_ID_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<String> USER_ID_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<String> PHASE_HOLDER = new ThreadLocal<>();
    /**
     * 调试模式开关：
     * 每个 run 在执行线程（含并行子线程）里独立保存，避免跨 run 串扰。
     */
    private static final ThreadLocal<Boolean> DEBUG_MODE_HOLDER = new ThreadLocal<>();

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

    public static void setDebugMode(boolean debugMode) {
        DEBUG_MODE_HOLDER.set(debugMode);
    }

    public static boolean isDebugMode() {
        Boolean enabled = DEBUG_MODE_HOLDER.get();
        return enabled != null && enabled;
    }

    public static void clearDebugMode() {
        DEBUG_MODE_HOLDER.remove();
    }

    public static void clearPhase() {
        PHASE_HOLDER.remove();
    }

    public static void clear() {
        RUN_ID_HOLDER.remove();
        USER_ID_HOLDER.remove();
        clearPhase();
        clearDebugMode();
    }
}
