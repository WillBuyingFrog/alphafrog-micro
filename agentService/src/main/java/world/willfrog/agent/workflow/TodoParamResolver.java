package world.willfrog.agent.workflow;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class TodoParamResolver {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_-]+)\\.output(?:\\.([A-Za-z0-9_.-]+))?}");

    public Map<String, Object> resolve(Map<String, Object> params, Map<String, TodoExecutionRecord> context) {
        Map<String, Object> source = params == null ? Map.of() : params;
        log.info("TodoParamResolver.resolve called with context keys: {}", context != null ? context.keySet() : "null");
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
            log.info("Resolving placeholder: todoId={}, path={}, raw={}", todoId, path, matcher.group(0));
            
            TodoExecutionRecord record = context.get(todoId);
            if (record == null) {
                log.warn("TodoExecutionRecord not found in context for todoId: {}. Available keys: {}", 
                        todoId, context.keySet());
            } else {
                log.info("Found record for todoId: {}, success={}, output length={}", 
                        todoId, record.isSuccess(), 
                        record.getOutput() != null ? record.getOutput().length() : 0);
            }
            
            Object replacement = readPath(record, path);
            log.info("Replacement result for {}.{}: {}", todoId, path, replacement);
            
            out.append(replacement == null ? matcher.group(0) : String.valueOf(replacement));
            last = matcher.end();
        }
        out.append(text.substring(last));
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private Object readPath(TodoExecutionRecord record, String path) {
        if (record == null) {
            log.debug("readPath: record is null");
            return null;
        }
        if (path == null || path.isBlank()) {
            log.debug("readPath: path is null/blank, returning raw output");
            return record.getOutput();
        }
        String raw = record.getOutput();
        if (raw == null || raw.isBlank()) {
            log.debug("readPath: raw output is null/blank");
            return null;
        }
        
        Object parsed;
        try {
            parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(raw, Object.class);
            log.info("readPath: JSON parsed successfully, type={}", parsed != null ? parsed.getClass().getSimpleName() : "null");
        } catch (Exception e) {
            log.warn("readPath: Failed to parse JSON output: {}", e.getMessage());
            return null;
        }

        Object cursor = parsed;
        String[] tokens = path.split("\\.");
        log.info("readPath: path tokens={}", (Object[]) tokens);
        
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            log.info("readPath: processing token[{}]='{}', cursor type={}", 
                    i, token, cursor != null ? cursor.getClass().getSimpleName() : "null");
            
            if (cursor instanceof Map<?, ?> map) {
                cursor = map.get(token);
                log.info("readPath: map.get('{}') = {}", token, 
                        cursor != null ? cursor.getClass().getSimpleName() : "null");
            } else if (cursor instanceof List<?> list) {
                int idx;
                try {
                    idx = Integer.parseInt(token);
                } catch (NumberFormatException e) {
                    log.warn("readPath: Cannot parse '{}' as integer for list index", token);
                    return null;
                }
                if (idx < 0 || idx >= list.size()) {
                    log.warn("readPath: Index {} out of bounds for list of size {}", idx, list.size());
                    return null;
                }
                cursor = list.get(idx);
                log.info("readPath: list.get({}) = {}", idx, 
                        cursor != null ? cursor.getClass().getSimpleName() : "null");
            } else {
                log.warn("readPath: Cannot navigate into type {} with token '{}'", 
                        cursor != null ? cursor.getClass().getSimpleName() : "null", token);
                return null;
            }
        }
        
        log.info("readPath: final result = {}", cursor);
        return cursor;
    }
}
