package world.willfrog.agent.workflow;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TodoParamResolver {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_-]+)\\.output(?:\\.([A-Za-z0-9_.-]+))?}");

    public Map<String, Object> resolve(Map<String, Object> params, Map<String, TodoExecutionRecord> context) {
        Map<String, Object> source = params == null ? Map.of() : params;
        Object resolved = resolveAny(source, context == null ? Map.of() : context);
        if (resolved instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return out;
        }
        return Map.of();
    }

    private Object resolveAny(Object input, Map<String, TodoExecutionRecord> context) {
        if (input == null) {
            return null;
        }
        if (input instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), resolveAny(entry.getValue(), context));
            }
            return out;
        }
        if (input instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object value : list) {
                out.add(resolveAny(value, context));
            }
            return out;
        }
        if (input instanceof String text) {
            return resolveString(text, context);
        }
        return input;
    }

    private Object resolveString(String text, Map<String, TodoExecutionRecord> context) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        if (!matcher.find()) {
            return text;
        }

        matcher.reset();
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            out.append(text, last, matcher.start());
            String todoId = matcher.group(1);
            String path = matcher.group(2);
            Object replacement = readPath(context.get(todoId), path);
            out.append(replacement == null ? matcher.group(0) : String.valueOf(replacement));
            last = matcher.end();
        }
        out.append(text.substring(last));
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private Object readPath(TodoExecutionRecord record, String path) {
        if (record == null) {
            return null;
        }
        if (path == null || path.isBlank()) {
            return record.getOutput();
        }
        String raw = record.getOutput();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Object parsed;
        try {
            parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(raw, Object.class);
        } catch (Exception e) {
            return null;
        }

        Object cursor = parsed;
        for (String token : path.split("\\.")) {
            if (cursor instanceof Map<?, ?> map) {
                cursor = map.get(token);
            } else if (cursor instanceof List<?> list) {
                int idx;
                try {
                    idx = Integer.parseInt(token);
                } catch (NumberFormatException e) {
                    return null;
                }
                if (idx < 0 || idx >= list.size()) {
                    return null;
                }
                cursor = list.get(idx);
            } else {
                return null;
            }
        }
        return cursor;
    }
}
