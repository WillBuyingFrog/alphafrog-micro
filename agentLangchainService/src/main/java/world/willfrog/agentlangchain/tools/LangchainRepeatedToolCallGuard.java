package world.willfrog.agentlangchain.tools;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class LangchainRepeatedToolCallGuard {

    static final int DEFAULT_MAX_IDENTICAL_CALLS = 2;

    private LangchainRepeatedToolCallGuard() {
    }

    static Decision beforeInvoke(String toolName, Map<String, Object> arguments, ObjectMapper objectMapper) {
        LangchainToolCallSignature signature = new LangchainToolCallSignature(
                toolName == null ? "" : toolName,
                canonicalArguments(arguments, objectMapper));
        int count = LangchainRepeatedToolCallContext.currentOrCreate().increment(signature);
        if (count > DEFAULT_MAX_IDENTICAL_CALLS) {
            return Decision.blocked(toolName, count, repeatedToolCallError(toolName, count, objectMapper));
        }
        if (count > 1) {
            return Decision.repeated(toolName, count, repeatedHint(toolName, count));
        }
        return Decision.allowed(toolName, count);
    }

    private static String canonicalArguments(Map<String, Object> arguments, ObjectMapper objectMapper) {
        try {
            Object canonical = canonicalize(arguments == null ? Map.of() : arguments);
            return objectMapper.writeValueAsString(canonical);
        } catch (Exception ignored) {
            return String.valueOf(arguments);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), canonicalize(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(canonicalize(item));
            }
            return result;
        }
        return value;
    }

    private static String repeatedToolCallError(String toolName, int count, ObjectMapper objectMapper) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", false);
        payload.put("code", "REPEATED_TOOL_CALL");
        payload.put("message", "repeated_tool_call: same tool and arguments were called repeatedly");
        payload.put("tool_name", toolName == null ? "" : toolName);
        payload.put("repeat_count", count);
        payload.put("_retry_hint_", repeatedHint(toolName, count));
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ignored) {
            return "{\"success\":false,\"code\":\"REPEATED_TOOL_CALL\",\"message\":\"repeated_tool_call\"}";
        }
    }

    private static String repeatedHint(String toolName, int count) {
        String name = toolName == null || toolName.isBlank() ? "the same tool" : toolName;
        return "Do not call " + name + " again with identical arguments. "
                + "Use the previous tool result, change arguments, or summarize/fail. "
                + "repeat_count=" + count;
    }

    record Decision(String toolName, int count, boolean blocked, boolean repeated, String outputOrHint) {
        static Decision allowed(String toolName, int count) {
            return new Decision(toolName, count, false, false, "");
        }

        static Decision repeated(String toolName, int count, String hint) {
            return new Decision(toolName, count, false, true, hint);
        }

        static Decision blocked(String toolName, int count, String output) {
            return new Decision(toolName, count, true, true, output);
        }
    }
}
