package world.willfrog.alphafrogmicro.common.utils;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 占位符解析器，递归解析 POJO / Map / JSONObject 中 String 字段的 ${ENV_VAR} 格式。
 *
 * <p>用于将配置 JSON 中的环境变量占位符替换为实际值，
 * 避免在数据库/Nacos 中存储敏感信息。</p>
 */
@Slf4j
public class PlaceholderResolver {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");
    private static final int MAX_RECURSIVE_DEPTH = 5;

    /**
     * 递归解析对象中的所有 String 字段（支持 POJO、Map、Iterable、Array）。
     */
    public static void resolve(Object obj) {
        if (obj == null) {
            return;
        }
        try {
            resolveInternal(obj, 0, new IdentityHashMap<>());
        } catch (IllegalAccessException e) {
            log.warn("[PlaceholderResolver] 反射解析失败", e);
        }
    }

    /**
     * 递归解析 fastjson2 JSONObject 中所有 String value 的占位符。
     */
    public static void resolveJsonObject(JSONObject jsonObject) {
        if (jsonObject == null) {
            return;
        }
        for (String key : jsonObject.keySet().toArray(new String[0])) {
            Object value = jsonObject.get(key);
            if (value instanceof String strValue) {
                String resolved = resolveString(strValue);
                if (!resolved.equals(strValue)) {
                    jsonObject.put(key, resolved);
                }
            } else if (value instanceof JSONObject nested) {
                resolveJsonObject(nested);
            } else if (value instanceof JSONArray array) {
                resolveJsonArray(array);
            }
        }
    }

    /**
     * 递归解析 fastjson2 JSONArray 中所有 String value 的占位符。
     */
    public static void resolveJsonArray(JSONArray jsonArray) {
        if (jsonArray == null) {
            return;
        }
        for (int i = 0; i < jsonArray.size(); i++) {
            Object value = jsonArray.get(i);
            if (value instanceof String strValue) {
                jsonArray.set(i, resolveString(strValue));
            } else if (value instanceof JSONObject nested) {
                resolveJsonObject(nested);
            } else if (value instanceof JSONArray nestedArray) {
                resolveJsonArray(nestedArray);
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void resolveInternal(Object obj, int depth, IdentityHashMap<Object, Boolean> visited)
            throws IllegalAccessException {
        if (obj == null || depth > MAX_RECURSIVE_DEPTH) {
            return;
        }
        if (visited.containsKey(obj)) {
            return;
        }
        visited.put(obj, Boolean.TRUE);

        Class<?> clazz = obj.getClass();
        // 跳过 JDK 内置类型和基础类型
        if (clazz.isPrimitive() || clazz.getName().startsWith("java.lang.") && !(obj instanceof String)) {
            return;
        }

        if (obj instanceof String) {
            // String 类型不需要递归，由调用方处理
            return;
        }

        if (obj instanceof Map map) {
            for (Object key : map.keySet().toArray()) {
                Object value = map.get(key);
                if (value instanceof String strValue) {
                    map.put(key, resolveString(strValue));
                } else {
                    resolveInternal(value, depth + 1, visited);
                }
            }
            return;
        }

        if (obj instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                resolveInternal(item, depth + 1, visited);
            }
            return;
        }

        if (clazz.isArray()) {
            int length = Array.getLength(obj);
            for (int i = 0; i < length; i++) {
                Object value = Array.get(obj, i);
                if (value instanceof String strValue) {
                    Array.set(obj, i, resolveString(strValue));
                } else {
                    resolveInternal(value, depth + 1, visited);
                }
            }
            return;
        }

        for (Field field : clazz.getDeclaredFields()) {
            field.setAccessible(true);
            Object value = field.get(obj);
            if (value == null) {
                continue;
            }

            if (value instanceof String strValue) {
                String resolved = resolveString(strValue);
                if (!resolved.equals(strValue)) {
                    field.set(obj, resolved);
                }
            } else if (!value.getClass().isPrimitive()
                    && !value.getClass().getName().startsWith("java.lang.")
                    && !value.getClass().getName().startsWith("java.time.")
                    && !value.getClass().isEnum()) {
                // 递归处理嵌套对象
                resolveInternal(value, depth + 1, visited);
            }
        }
    }

    /**
     * 解析单个字符串中的占位符，支持字符串内嵌多个 ${ENV_VAR} 及递归解析。
     */
    public static String resolveString(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String result = value;
        for (int i = 0; i < MAX_RECURSIVE_DEPTH; i++) {
            String newResult = resolveSinglePass(result);
            if (newResult.equals(result)) {
                break;
            }
            result = newResult;
        }
        return result;
    }

    private static String resolveSinglePass(String value) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(value);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String envVar = matcher.group(1);
            String envValue = System.getenv(envVar);
            if (envValue != null && !envValue.isEmpty()) {
                // 对替换值中的 $ 和 \ 做转义，避免 StringBuffer 将其视为特殊引用
                matcher.appendReplacement(sb, Matcher.quoteReplacement(envValue));
            } else {
                log.warn("[PlaceholderResolver] 环境变量未设置: {}, 保留原值", envVar);
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
