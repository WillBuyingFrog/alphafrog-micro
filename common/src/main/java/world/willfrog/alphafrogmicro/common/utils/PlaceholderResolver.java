package world.willfrog.alphafrogmicro.common.utils;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;

/**
 * 占位符解析器，递归解析 POJO 中 String 字段的 ${ENV_VAR} 格式。
 *
 * <p>用于将配置 JSON 中的环境变量占位符替换为实际值，
 * 避免在数据库/Nacos 中存储敏感信息。</p>
 */
@Slf4j
public class PlaceholderResolver {

    private static final String PREFIX = "${";
    private static final String SUFFIX = "}";

    /**
     * 递归解析对象中的所有 String 字段。
     */
    public static void resolve(Object obj) {
        if (obj == null) {
            return;
        }
        try {
            resolveInternal(obj, 0);
        } catch (IllegalAccessException e) {
            log.warn("[PlaceholderResolver] 反射解析失败", e);
        }
    }

    private static void resolveInternal(Object obj, int depth) throws IllegalAccessException {
        if (obj == null || depth > 5) {
            return;
        }

        Class<?> clazz = obj.getClass();
        // 跳过 JDK 内置类型和基础类型
        if (clazz.isPrimitive() || clazz.getName().startsWith("java.lang.") && !(obj instanceof String)) {
            return;
        }

        if (obj instanceof String str) {
            // String 类型不需要递归，由调用方处理
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
                resolveInternal(value, depth + 1);
            }
        }
    }

    /**
     * 解析单个字符串中的占位符。
     */
    public static String resolveString(String value) {
        if (value == null || !value.startsWith(PREFIX) || !value.endsWith(SUFFIX)) {
            return value;
        }
        String envVar = value.substring(PREFIX.length(), value.length() - SUFFIX.length());
        String envValue = System.getenv(envVar);
        if (envValue != null && !envValue.isEmpty()) {
            return envValue;
        }
        log.warn("[PlaceholderResolver] 环境变量未设置: {}, 保留原值", envVar);
        return value;
    }
}
