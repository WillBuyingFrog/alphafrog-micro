package world.willfrog.agent.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * planning / sub-agent planning 的结构化输出工具。
 */
public final class StructuredPlanningSupport {

    public static final String CATEGORY_JSON_PARSE_ERROR = "JSON_PARSE_ERROR";
    public static final String CATEGORY_SCHEMA_VALIDATION_ERROR = "SCHEMA_VALIDATION_ERROR";

    private static final Set<String> TODO_TYPES = Set.of("TOOL_CALL", "SUB_AGENT", "THOUGHT");
    private static final Set<String> EXECUTION_MODES = Set.of("AUTO", "FORCE_SIMPLE", "FORCE_SUB_AGENT");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_-]+)\\.output(?:\\.([A-Za-z0-9_.-]+))?}");

    private StructuredPlanningSupport() {
    }

    public static JsonNode parseStructuredJson(ObjectMapper objectMapper, String raw) {
        String text = stripFence(raw);
        if (text.isBlank()) {
            throw new StructuredPlanningException(CATEGORY_JSON_PARSE_ERROR, "empty_response");
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            throw new StructuredPlanningException(CATEGORY_JSON_PARSE_ERROR, safeMessage(e));
        }
    }

    public static ValidationResult validateTodoPlan(JsonNode root, int maxTodos, Set<String> toolWhitelist) {
        if (root == null || !root.isObject()) {
            return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "todo_plan_not_object");
        }
        JsonNode analysisNode = root.get("analysis");
        if (analysisNode == null || !analysisNode.isTextual()) {
            return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "todo_plan_missing_analysis");
        }
        JsonNode itemsNode = root.get("items");
        if (itemsNode == null || !itemsNode.isArray()) {
            return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "todo_plan_missing_items");
        }
        if (itemsNode.isEmpty()) {
            return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "todo_plan_items_empty");
        }
        if (itemsNode.size() > maxTodos) {
            return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "todo_plan_items_exceed_max");
        }

        int index = 0;
        for (JsonNode itemNode : itemsNode) {
            if (!itemNode.isObject()) {
                return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "todo_item_not_object@" + index);
            }
            if (!isText(itemNode.get("id"))) {
                return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "todo_item_missing_id@" + index);
            }
            if (!itemNode.has("sequence") || !itemNode.get("sequence").isInt()) {
                return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "todo_item_missing_sequence@" + index);
            }
            String type = upper(itemNode.path("type").asText(""));
            if (!TODO_TYPES.contains(type)) {
                return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "todo_item_invalid_type@" + index);
            }
            if (!isText(itemNode.get("toolName"))) {
                return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "todo_item_missing_tool_name@" + index);
            }
            String toolName = itemNode.path("toolName").asText("");
            if ("TOOL_CALL".equals(type) && (toolWhitelist == null || !toolWhitelist.contains(toolName))) {
                return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "todo_item_tool_not_allowed@" + index);
            }
            JsonNode params = itemNode.get("params");
            if (params == null || !params.isObject()) {
                return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "todo_item_params_not_object@" + index);
            }
            ValidationResult placeholderCheck = validatePlaceholders(params, true);
            if (!placeholderCheck.valid()) {
                return ValidationResult.invalid(placeholderCheck.category(), placeholderCheck.message() + "@" + index);
            }
            if (!isText(itemNode.get("reasoning"))) {
                return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "todo_item_missing_reasoning@" + index);
            }
            String mode = upper(itemNode.path("executionMode").asText(""));
            if (!EXECUTION_MODES.contains(mode)) {
                return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "todo_item_invalid_execution_mode@" + index);
            }
            index++;
        }
        return ValidationResult.valid();
    }

    public static ValidationResult validateSubAgentPlan(JsonNode root, int maxSteps, Set<String> toolWhitelist) {
        if (root == null || !root.isObject()) {
            return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "sub_agent_plan_not_object");
        }
        JsonNode stepsNode = root.get("steps");
        if (stepsNode == null || !stepsNode.isArray()) {
            return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "sub_agent_plan_missing_steps");
        }
        if (stepsNode.isEmpty()) {
            return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "sub_agent_plan_steps_empty");
        }
        if (stepsNode.size() > maxSteps) {
            return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "sub_agent_plan_steps_exceed_max");
        }

        int index = 0;
        for (JsonNode stepNode : stepsNode) {
            if (!stepNode.isObject()) {
                return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "sub_agent_step_not_object@" + index);
            }
            String tool = stepNode.path("tool").asText("");
            if (tool.isBlank()) {
                return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "sub_agent_step_missing_tool@" + index);
            }
            if (toolWhitelist == null || !toolWhitelist.contains(tool)) {
                return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "sub_agent_step_tool_not_allowed@" + index);
            }
            JsonNode args = stepNode.get("args");
            if (args == null || !args.isObject()) {
                return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "sub_agent_step_args_not_object@" + index);
            }
            ValidationResult placeholderCheck = validatePlaceholders(args, false);
            if (!placeholderCheck.valid()) {
                return ValidationResult.invalid(placeholderCheck.category(), placeholderCheck.message() + "@" + index);
            }
            index++;
        }
        return ValidationResult.valid();
    }

    public static ValidationResult validatePlaceholders(JsonNode node, boolean todoOnly) {
        if (node == null || node.isNull()) {
            return ValidationResult.valid();
        }
        if (node.isObject()) {
            for (JsonNode child : node) {
                ValidationResult result = validatePlaceholders(child, todoOnly);
                if (!result.valid()) {
                    return result;
                }
            }
            return ValidationResult.valid();
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                ValidationResult result = validatePlaceholders(child, todoOnly);
                if (!result.valid()) {
                    return result;
                }
            }
            return ValidationResult.valid();
        }
        if (!node.isTextual()) {
            return ValidationResult.valid();
        }
        String value = node.asText();
        if (!value.contains("${")) {
            return ValidationResult.valid();
        }

        Matcher matcher = PLACEHOLDER.matcher(value);
        List<String> refs = new ArrayList<>();
        while (matcher.find()) {
            refs.add(matcher.group(1));
        }
        if (refs.isEmpty()) {
            return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "placeholder_invalid_format");
        }
        for (String ref : refs) {
            if (todoOnly && !ref.startsWith("todo_")) {
                return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "placeholder_must_use_todo_ref");
            }
            if (!todoOnly && !(ref.startsWith("step_") || ref.startsWith("todo_"))) {
                return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "placeholder_must_use_step_or_todo_ref");
            }
        }
        return ValidationResult.valid();
    }

    public static Map<String, Object> todoPlanningJsonSchema() {
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("analysis", "items"),
                "properties", Map.of(
                        "analysis", Map.of("type", "string"),
                        "items", Map.of(
                                "type", "array",
                                "minItems", 1,
                                "items", Map.of(
                                        "type", "object",
                                        "additionalProperties", false,
                                        "required", List.of("id", "sequence", "type", "toolName", "params", "reasoning", "executionMode"),
                                        "properties", Map.of(
                                                "id", Map.of("type", "string"),
                                                "sequence", Map.of("type", "integer"),
                                                "type", Map.of("type", "string", "enum", List.of("TOOL_CALL", "SUB_AGENT", "THOUGHT")),
                                                "toolName", Map.of("type", "string"),
                                                "params", Map.of("type", "object"),
                                                "reasoning", Map.of("type", "string"),
                                                "executionMode", Map.of("type", "string", "enum", List.of("AUTO", "FORCE_SIMPLE", "FORCE_SUB_AGENT"))
                                        )
                                )
                        )
                )
        );
    }

    public static Map<String, Object> subAgentPlanningJsonSchema() {
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("steps", "expected"),
                "properties", Map.of(
                        "expected", Map.of("type", "string"),
                        "steps", Map.of(
                                "type", "array",
                                "minItems", 1,
                                "items", Map.of(
                                        "type", "object",
                                        "additionalProperties", false,
                                        "required", List.of("tool", "args", "note"),
                                        "properties", Map.of(
                                                "tool", Map.of("type", "string"),
                                                "args", Map.of("type", "object"),
                                                "note", Map.of("type", "string")
                                        )
                                )
                        )
                )
        );
    }

    private static String stripFence(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstLineEnd = text.indexOf('\n');
        if (firstLineEnd < 0) {
            return "";
        }
        String body = text.substring(firstLineEnd + 1).trim();
        if (body.endsWith("```")) {
            body = body.substring(0, body.length() - 3).trim();
        }
        return body;
    }

    private static boolean isText(JsonNode node) {
        return node != null && node.isTextual() && !node.asText("").isBlank();
    }

    private static String upper(String text) {
        return text == null ? "" : text.trim().toUpperCase();
    }

    private static String safeMessage(Throwable e) {
        String message = e == null ? "" : e.getMessage();
        return message == null ? "" : message;
    }

    public record ValidationResult(boolean valid, String category, String message) {
        public static ValidationResult valid() {
            return new ValidationResult(true, "", "");
        }

        public static ValidationResult invalid(String category, String message) {
            return new ValidationResult(false, category == null ? "" : category, message == null ? "" : message);
        }
    }

    public static class StructuredPlanningException extends RuntimeException {
        private final String category;

        public StructuredPlanningException(String category, String message) {
            super(message);
            this.category = category == null ? CATEGORY_SCHEMA_VALIDATION_ERROR : category;
        }

        public String category() {
            return category;
        }
    }
}
