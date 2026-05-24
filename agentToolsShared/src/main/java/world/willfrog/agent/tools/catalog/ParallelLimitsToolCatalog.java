package world.willfrog.agent.tools.catalog;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical LLM-visible specification for {@code checkParallelLimits}.
 *
 * <p>Some providers reject or drop zero-arg {@code @Tool} reflections; this helper guarantees the
 * tool is always present in run-scoped catalogs that are sent to the model.</p>
 */
public final class ParallelLimitsToolCatalog {

    public static final String TOOL_NAME = "checkParallelLimits";

    private static final String DESCRIPTION =
            "查询当前批量/并行查询限制。返回 search 和 daily 工具组的热加载 maxItems，以及各工具组包含哪些工具。"
                    + "使用任何批量参数前必须先调用本工具；如果没有本工具，默认并行查询关闭。";

    private ParallelLimitsToolCatalog() {
    }

    public static ToolSpecification specification() {
        return ToolSpecification.builder()
                .name(TOOL_NAME)
                .description(DESCRIPTION)
                .parameters(JsonObjectSchema.builder()
                        .additionalProperties(false)
                        .build())
                .build();
    }

    /**
     * Ensures {@link #TOOL_NAME} is present and uses the canonical schema (insert at front when missing).
     */
    public static void ensureRegistered(List<ToolSpecification> specifications) {
        if (specifications == null) {
            return;
        }
        boolean present = specifications.stream()
                .anyMatch(spec -> spec != null && TOOL_NAME.equals(spec.name()));
        if (!present) {
            specifications.add(0, specification());
        }
    }

    /**
     * Dedupes by tool name and always applies the canonical {@link #TOOL_NAME} specification last.
     */
    public static List<ToolSpecification> mergeCanonical(List<ToolSpecification> specifications) {
        Map<String, ToolSpecification> byName = new LinkedHashMap<>();
        if (specifications != null) {
            for (ToolSpecification spec : specifications) {
                if (spec == null || spec.name() == null || spec.name().isBlank()) {
                    continue;
                }
                byName.put(spec.name(), spec);
            }
        }
        byName.put(TOOL_NAME, specification());
        return List.copyOf(byName.values());
    }

    public static List<ToolSpecification> mergeCanonicalFromBeans(Object... toolBeans) {
        List<ToolSpecification> merged = new ArrayList<>();
        for (Object bean : toolBeans) {
            if (bean == null) {
                continue;
            }
            merged.addAll(dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationsFrom(bean));
        }
        return mergeCanonical(merged);
    }
}
