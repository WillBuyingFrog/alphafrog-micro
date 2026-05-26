package world.willfrog.alphafrogmicro.domestic.fetch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.common.fetchcatalog.*;

import java.util.*;

/**
 * 基于 JSON Catalog 配置的任务参数解析器。
 * <p>负责根据 {@link TaskVariantConfig#getParamDefs()} 从原始 taskParams 中提取参数、
 * 应用默认值、做类型转换（string/number/boolean）。
 * 对于带点号的参数名（如 trade_dates.start_date），支持嵌套 Map 取值。</p>
 *
 * @see FetchCatalogConfigLoader
 */
@Component
@Slf4j
public class FetchTaskParamResolver {

    private final FetchCatalogConfigLoader catalogLoader;

    public FetchTaskParamResolver(FetchCatalogConfigLoader catalogLoader) {
        this.catalogLoader = catalogLoader;
    }

    /**
     * 解析任务参数。
     *
     * @param taskName    任务名称（如 index_quote）
     * @param taskSubType task_sub_type
     * @param taskParams  原始的 task_params Map
     * @return 解析后的参数 Map，已应用默认值和类型转换
     */
    public Map<String, Object> resolve(String taskName, int taskSubType, Map<String, Object> taskParams) {
        Map<String, Object> result = new LinkedHashMap<>();
        TaskVariantConfig variant = catalogLoader.findTaskVariant(taskName, taskSubType);
        if (variant == null) {
            log.warn("No task variant found for taskName={}, subType={}, returning raw params", taskName, taskSubType);
            if (taskParams != null) result.putAll(taskParams);
            return result;
        }

        if (variant.getParamDefs() != null) {
            for (Map.Entry<String, ParamDef> entry : variant.getParamDefs().entrySet()) {
                String key = entry.getKey();
                ParamDef def = entry.getValue();
                Object value = getNestedValue(taskParams, key);
                if (value == null && "index_batch_limit".equals(key)) {
                    value = getNestedValue(taskParams, "index_limit");
                }
                if (value == null && "index_batch_limit".equals(key)) {
                    value = getNestedValue(taskParams, "limit");
                }
                if (value == null) {
                    value = def.getDefaultValue();
                }
                Object converted = convertType(value, def.getType());
                result.put(key, converted);
            }
        }
        // 保留 paramDefs 中未定义但 taskParams 中存在的额外字段
        if (taskParams != null) {
            for (Map.Entry<String, Object> entry : taskParams.entrySet()) {
                if (!result.containsKey(entry.getKey())) {
                    result.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return result;
    }

    /**
     * 从可能的嵌套 Map 中按点号分隔的键路径取值。
     * <p>例如 key="trade_dates.start_date" 会从 map.get("trade_dates")、2级 map 中取 "start_date"。</p>
     */
    private Object getNestedValue(Map<String, Object> map, String key) {
        if (map == null) return null;
        if (key.contains(".")) {
            String[] parts = key.split("\\.");
            Object current = map;
            for (String part : parts) {
                if (current instanceof Map<?, ?> m) {
                    current = m.get(part);
                } else {
                    return null;
                }
            }
            return current;
        }
        return map.get(key);
    }

    /**
     * 根据 paramDef 中的 type 字段将值转为目标类型。
     *
     * @param value 待转换的原始值
     * @param type  目标类型，可为 number / boolean / string
     * @return 转换后的值，转换失败时原样返回
     */
    private Object convertType(Object value, String type) {
        if (value == null) return null;
        if (type == null) return value;
        return switch (type) {
            case "number" -> {
                if (value instanceof Number n) yield n.intValue();
                try {
                    yield Integer.parseInt(value.toString().trim());
                } catch (NumberFormatException e) {
                    yield value;
                }
            }
            case "boolean" -> {
                if (value instanceof Boolean b) yield b;
                yield Boolean.parseBoolean(value.toString().trim());
            }
            case "string" -> value.toString();
            default -> value;
        };
    }
}
