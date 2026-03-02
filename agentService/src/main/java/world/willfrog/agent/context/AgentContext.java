package world.willfrog.agent.context;

import java.util.LinkedHashMap;
import java.util.Map;

public class AgentContext {
    private static final ThreadLocal<String> RUN_ID_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<String> USER_ID_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<String> PHASE_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<String> STAGE_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<String> TODO_ID_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<Integer> TODO_SEQUENCE_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<Integer> SUB_AGENT_STEP_INDEX_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<Integer> PYTHON_REFINE_ATTEMPT_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<String> DECISION_TRACE_ID_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<String> DECISION_STAGE_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<String> DECISION_EXCERPT_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<StructuredOutputSpec> STRUCTURED_OUTPUT_SPEC_HOLDER = new ThreadLocal<>();
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

    public static void setStage(String stage) {
        STAGE_HOLDER.set(stage);
    }

    public static String getStage() {
        return STAGE_HOLDER.get();
    }

    public static void setTodoContext(String todoId, Integer sequence) {
        TODO_ID_HOLDER.set(todoId);
        TODO_SEQUENCE_HOLDER.set(sequence);
    }

    public static String getTodoId() {
        return TODO_ID_HOLDER.get();
    }

    public static Integer getTodoSequence() {
        return TODO_SEQUENCE_HOLDER.get();
    }

    public static void setSubAgentStepIndex(Integer stepIndex) {
        SUB_AGENT_STEP_INDEX_HOLDER.set(stepIndex);
    }

    public static Integer getSubAgentStepIndex() {
        return SUB_AGENT_STEP_INDEX_HOLDER.get();
    }

    public static void setPythonRefineAttempt(Integer attempt) {
        PYTHON_REFINE_ATTEMPT_HOLDER.set(attempt);
    }

    public static Integer getPythonRefineAttempt() {
        return PYTHON_REFINE_ATTEMPT_HOLDER.get();
    }

    public static void setDecisionContext(String traceId, String stage, String excerpt) {
        DECISION_TRACE_ID_HOLDER.set(traceId);
        DECISION_STAGE_HOLDER.set(stage);
        DECISION_EXCERPT_HOLDER.set(excerpt);
    }

    public static String getDecisionTraceId() {
        return DECISION_TRACE_ID_HOLDER.get();
    }

    public static String getDecisionStage() {
        return DECISION_STAGE_HOLDER.get();
    }

    public static String getDecisionExcerpt() {
        return DECISION_EXCERPT_HOLDER.get();
    }

    public static void setStructuredOutputSpec(StructuredOutputSpec spec) {
        STRUCTURED_OUTPUT_SPEC_HOLDER.set(spec);
    }

    public static StructuredOutputSpec getStructuredOutputSpec() {
        return STRUCTURED_OUTPUT_SPEC_HOLDER.get();
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

    public static void clearStage() {
        STAGE_HOLDER.remove();
    }

    public static void clearTodoContext() {
        TODO_ID_HOLDER.remove();
        TODO_SEQUENCE_HOLDER.remove();
    }

    public static void clearSubAgentStepIndex() {
        SUB_AGENT_STEP_INDEX_HOLDER.remove();
    }

    public static void clearPythonRefineAttempt() {
        PYTHON_REFINE_ATTEMPT_HOLDER.remove();
    }

    public static void clearDecisionContext() {
        DECISION_TRACE_ID_HOLDER.remove();
        DECISION_STAGE_HOLDER.remove();
        DECISION_EXCERPT_HOLDER.remove();
    }

    public static void clearStructuredOutputSpec() {
        STRUCTURED_OUTPUT_SPEC_HOLDER.remove();
    }

    public static void clear() {
        RUN_ID_HOLDER.remove();
        USER_ID_HOLDER.remove();
        clearPhase();
        clearStage();
        clearTodoContext();
        clearSubAgentStepIndex();
        clearPythonRefineAttempt();
        clearDecisionContext();
        clearStructuredOutputSpec();
        clearDebugMode();
    }

    public static final class StructuredOutputSpec {
        private final String schemaName;
        private final boolean strict;
        private final Map<String, Object> schema;
        private final boolean requireProviderParameters;
        private final boolean allowProviderFallbacks;

        public StructuredOutputSpec(String schemaName,
                                    boolean strict,
                                    Map<String, Object> schema,
                                    boolean requireProviderParameters,
                                    boolean allowProviderFallbacks) {
            this.schemaName = schemaName == null ? "" : schemaName;
            this.strict = strict;
            this.schema = schema == null ? Map.of() : Map.copyOf(schema);
            this.requireProviderParameters = requireProviderParameters;
            this.allowProviderFallbacks = allowProviderFallbacks;
        }

        public String schemaName() {
            return schemaName;
        }

        public boolean strict() {
            return strict;
        }

        public Map<String, Object> schema() {
            return schema;
        }

        public boolean requireProviderParameters() {
            return requireProviderParameters;
        }

        public boolean allowProviderFallbacks() {
            return allowProviderFallbacks;
        }

        public Map<String, Object> asResponseFormat() {
            Map<String, Object> jsonSchema = new LinkedHashMap<>();
            jsonSchema.put("name", schemaName);
            jsonSchema.put("strict", strict);
            jsonSchema.put("schema", schema);

            Map<String, Object> responseFormat = new LinkedHashMap<>();
            responseFormat.put("type", "json_schema");
            responseFormat.put("json_schema", jsonSchema);
            return responseFormat;
        }
    }
}
