package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import world.willfrog.agent.workflow.CompletedTodoInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 引用聚合服务,负责从已完成 Todo 输出中抽取搜索引用并生成统一引用表。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>从已完成 Todo 的 output(通常是工具调用结果 JSON)中递归扫描 {@code citations} 数组。</li>
 *   <li>按 URL 归一化(去 fragment、去尾斜杠、小写化)做去重。</li>
 *   <li>重新编号为从 1 开始的全局引用编号,生成 {@link CitationMap}。</li>
 *   <li>提供 {@link #buildPromptBlock} 将引用表注入最终回答提示词。</li>
 *   <li>提供 snapshot 序列化/反序列化辅助,用于持久化与回放。</li>
 * </ul>
 *
 * <h3>消费方</h3>
 * <ul>
 *   <li>{@link LinearWorkflowExecutor}、{@link world.willfrog.agent.workflow.DagWorkflowExecutor} —
 *       在生成最终回答前调用 {@link #buildCitationMap} + {@link #buildPromptBlock}。</li>
 *   <li>{@link AgentFinalAnswerParser} — 在解析最终回答时引用 {@link CitationMap} 校验 [N] 编号。</li>
 *   <li>{@code AgentRunExecutor#buildSnapshotJson} — 通过 {@link #toSnapshotMap} 把引用表写入 snapshot。</li>
 * </ul>
 *
 * <h3>容量限制</h3>
 * 全局最多保留 {@link #MAX_CITATIONS} 条引用,标题最多 {@link #MAX_TITLE_LENGTH} 字符。
 *
 * @see Citation
 * @see CitationMap
 */
@Service
@RequiredArgsConstructor
public class AgentCitationService {

    /** 最多保留的引用条目数,达到上限后停止收集,防止 prompt 过长 */
    private static final int MAX_CITATIONS = 50;
    /** 单条引用 title 的最大字符数,超出会被截断 */
    private static final int MAX_TITLE_LENGTH = 160;

    private final ObjectMapper objectMapper;

    /**
     * 从已完成 Todo 列表中聚合引用,生成全局编号的引用表。
     *
     * <h4>处理流程</h4>
     * <ol>
     *   <li>遍历所有 todo,递归扫描其 output JSON 中的 {@code citations} 数组。</li>
     *   <li>按 URL 归一化键(小写、去 fragment、去尾斜杠)去重,首次出现的优先保留。</li>
     *   <li>按 LinkedHashMap 维护的发现顺序,重新分配从 1 开始的全局 index。</li>
     *   <li>达到 {@link #MAX_CITATIONS} 上限即停止。</li>
     * </ol>
     *
     * @param completedTodos 已完成 Todo 列表,允许为 null/空
     * @return 全局引用表,无引用时返回 {@link CitationMap#empty()}
     */
    public CitationMap buildCitationMap(List<CompletedTodoInfo> completedTodos) {
        if (completedTodos == null || completedTodos.isEmpty()) {
            return CitationMap.empty();
        }
        // 使用 LinkedHashMap 保留首次发现顺序,key 为归一化 URL
        LinkedHashMap<String, Citation> byUrl = new LinkedHashMap<>();
        for (CompletedTodoInfo todo : completedTodos) {
            collectFromTodo(todo, byUrl);
            if (byUrl.size() >= MAX_CITATIONS) {
                break;
            }
        }
        if (byUrl.isEmpty()) {
            return CitationMap.empty();
        }
        // 重新编号:第 1 个引用 index=1,以此类推
        List<Citation> citations = new ArrayList<>();
        int index = 1;
        for (Citation citation : byUrl.values()) {
            if (index > MAX_CITATIONS) {
                break;
            }
            citations.add(citation.withIndex(index++));
        }
        return new CitationMap(citations);
    }

    /**
     * 将引用表渲染为最终回答提示词的引用清单块。
     *
     * <p>返回文本包含编号、标题、URL,以及"相关性需谨慎"的警告(若有)。
     * 末尾会附上引用使用约定(让 LLM 在句子后标注 [N])。</p>
     *
     * <p>调用方一般是 LinearWorkflowExecutor / DagWorkflowExecutor / ReactTodoExecutor
     * 在构造最终回答 prompt 时,将此 block 拼接到上下文末尾。</p>
     *
     * @param citationMap 引用表,空时返回空串
     * @return 可直接拼到 prompt 的引用清单文本
     */
    public String buildPromptBlock(CitationMap citationMap) {
        if (citationMap == null || citationMap.isEmpty()) {
            return "";
        }
        StringBuilder block = new StringBuilder();
        block.append("\n\n可引用来源（只能使用下列编号，不要自行编造编号）：\n");
        for (Citation citation : citationMap.citations()) {
            block.append("[")
                    .append(citation.index())
                    .append("] ")
                    // 优先用标题,缺失时退回 URL,避免出现空标题
                    .append(citation.title().isBlank() ? citation.url() : citation.title())
                    .append(" - ")
                    .append(citation.url());
            // 当引用存在质量风险(实体不匹配、未裁判、有警告)时,给 LLM 一个提示
            if (citation.hasQualityRisk()) {
                block.append("（相关性需谨慎");
                if (!citation.relevanceWarning().isBlank()) {
                    block.append("：").append(citation.relevanceWarning());
                }
                block.append("）");
            }
            block.append("\n");
        }
        block.append("若回答使用了这些搜索证据，请在对应句子后标注来源编号，如 [1]。");
        return block.toString();
    }

    /**
     * 将引用表转换为 snapshot JSON 中的 Map 结构。
     *
     * <p>返回的结构形如 {@code {"citations": [{...}, {...}]}},
     * 可直接放入 snapshot 的 citation_map 字段。</p>
     *
     * @param citationMap 引用表
     * @return Map 结构,空时返回空 Map
     */
    public Map<String, Object> toSnapshotMap(CitationMap citationMap) {
        if (citationMap == null || citationMap.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Citation citation : citationMap.citations()) {
            rows.add(citation.toMap());
        }
        return Map.of("citations", rows);
    }

    /**
     * 从 snapshot 中反序列化引用表,用于历史 run 回放或追问场景。
     *
     * <p>容错处理:</p>
     * <ul>
     *   <li>输入既支持 {@code {"citations": [...]}} 又支持顶层数组形式。</li>
     *   <li>字段命名兼容 camelCase(sourceTodoId)和 snake_case(source_todo_id)。</li>
     *   <li>缺失字段时使用合理默认(如 entityMatch 默认 true、relevanceJudged 默认 true)。</li>
     *   <li>解析异常时返回空引用表而非抛出,保证调用方稳定。</li>
     * </ul>
     *
     * @param snapshotCitationMap snapshot 中的 citation_map 字段
     * @return 反序列化得到的引用表
     */
    public CitationMap fromSnapshotMap(Object snapshotCitationMap) {
        if (snapshotCitationMap == null) {
            return CitationMap.empty();
        }
        try {
            JsonNode root = objectMapper.valueToTree(snapshotCitationMap);
            JsonNode citations = root == null ? null : root.get("citations");
            // 兼容顶层就是数组的旧 snapshot 格式
            if (citations == null && root != null && root.isArray()) {
                citations = root;
            }
            if (citations == null || !citations.isArray()) {
                return CitationMap.empty();
            }
            List<Citation> rows = new ArrayList<>();
            for (JsonNode item : citations) {
                if (item == null || !item.isObject()) {
                    continue;
                }
                // 缺失 index 时按数组顺序生成,保证编号连续
                int index = item.path("index").asInt(rows.size() + 1);
                rows.add(new Citation(
                        index,
                        // 兼容 originalIndex / original_index 两种命名
                        item.path("originalIndex").asInt(item.path("original_index").asInt(0)),
                        truncate(text(item, "title"), MAX_TITLE_LENGTH),
                        text(item, "url"),
                        // 兼容 sourceTodoId / source_todo_id
                        text(item, "sourceTodoId").isBlank() ? text(item, "source_todo_id") : text(item, "sourceTodoId"),
                        // entityMatch 缺失时默认 true(向后兼容旧 snapshot)
                        item.path("entityMatch").isMissingNode() || item.path("entityMatch").asBoolean(true),
                        // relevanceJudged 缺失时默认 true
                        item.path("relevanceJudged").isMissingNode() ? Boolean.TRUE : item.path("relevanceJudged").asBoolean(),
                        text(item, "relevanceWarning").isBlank() ? text(item, "relevance_warning") : text(item, "relevanceWarning")
                ));
                if (rows.size() >= MAX_CITATIONS) {
                    break;
                }
            }
            return rows.isEmpty() ? CitationMap.empty() : new CitationMap(rows);
        } catch (Exception ignored) {
            return CitationMap.empty();
        }
    }

    /**
     * 从 snapshot 中的 completed_items 数组重新构建引用表。
     *
     * <p>用于追问/重播场景:历史 run 的 snapshot 里 completed_items 包含每个 Todo 的 output,
     * 通过本方法可以重新跑一次引用聚合,得到与原始 run 等价的引用表。</p>
     *
     * @param completedItems snapshot 中的 completed_items 字段(数组形式)
     * @return 引用表
     */
    public CitationMap buildCitationMapFromSnapshotCompletedItems(Object completedItems) {
        if (completedItems == null) {
            return CitationMap.empty();
        }
        try {
            JsonNode root = objectMapper.valueToTree(completedItems);
            if (root == null || !root.isArray()) {
                return CitationMap.empty();
            }
            // 把 JSON 数组重组为 CompletedTodoInfo 列表,以复用 buildCitationMap 的扫描逻辑
            List<CompletedTodoInfo> todos = new ArrayList<>();
            for (JsonNode item : root) {
                if (item == null || !item.isObject()) {
                    continue;
                }
                todos.add(CompletedTodoInfo.builder()
                        .todoId(firstText(item, "todoId", "todo_id", "id"))
                        .description(firstText(item, "description"))
                        .summary(firstText(item, "summary"))
                        .output(firstText(item, "output"))
                        .build());
            }
            return buildCitationMap(todos);
        } catch (Exception ignored) {
            return CitationMap.empty();
        }
    }

    /**
     * 从单个已完成 Todo 的 output 中扫描引用,并合并到去重表中。
     *
     * <p>output 通常是工具调用结果 JSON(搜索/RAG 等会返回 citations 字段)。
     * 非 JSON 文本输出被视为无引用,直接跳过。</p>
     */
    private void collectFromTodo(CompletedTodoInfo todo, LinkedHashMap<String, Citation> byUrl) {
        if (todo == null || todo.getOutput() == null || todo.getOutput().isBlank()) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(todo.getOutput());
            collectCitationArrays(root, todo, byUrl);
        } catch (Exception ignored) {
            // 普通文本输出不是错误，直接跳过。
        }
    }

    /**
     * 递归扫描 JSON 树,寻找所有名为 {@code citations} 的数组节点并收集其内容。
     *
     * <p>之所以递归是因为工具输出结构层级不固定:有时 {@code citations} 在顶层,
     * 有时嵌在 {@code result.data.citations} 等深层路径中。
     * 达到 {@link #MAX_CITATIONS} 上限即停止递归,避免无谓遍历。</p>
     */
    private void collectCitationArrays(JsonNode node, CompletedTodoInfo todo, LinkedHashMap<String, Citation> byUrl) {
        if (node == null || node.isNull() || byUrl.size() >= MAX_CITATIONS) {
            return;
        }
        if (node.isObject()) {
            // 当前对象自身有 citations 数组,直接收集
            JsonNode citations = node.get("citations");
            if (citations != null && citations.isArray()) {
                collectCitations(citations, todo, byUrl);
            }
            // 继续向下递归其他字段,因为 citations 可能多处出现
            node.fields().forEachRemaining(entry -> {
                if (byUrl.size() < MAX_CITATIONS) {
                    collectCitationArrays(entry.getValue(), todo, byUrl);
                }
            });
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                if (byUrl.size() >= MAX_CITATIONS) {
                    break;
                }
                collectCitationArrays(child, todo, byUrl);
            }
        }
    }

    /**
     * 解析一个 citations JSON 数组,把其中每条引用转为 {@link Citation} 加入去重表。
     *
     * <p>关键步骤:</p>
     * <ul>
     *   <li>用 {@link #normalizeUrlKey} 归一化 URL 作为去重 key。</li>
     *   <li>已存在则跳过(LinkedHashMap 保留首次出现的版本)。</li>
     *   <li>新增时 index 暂为 0,等所有 todo 处理完再统一重新编号。</li>
     *   <li>记录引用来源 todoId,便于后续追踪。</li>
     *   <li>读取 entityMatch / relevanceJudged / relevanceWarning 等相关性元数据。</li>
     * </ul>
     */
    private void collectCitations(JsonNode citations, CompletedTodoInfo todo, LinkedHashMap<String, Citation> byUrl) {
        for (JsonNode item : citations) {
            if (byUrl.size() >= MAX_CITATIONS) {
                break;
            }
            if (item == null || !item.isObject()) {
                continue;
            }
            String url = text(item, "url");
            String key = normalizeUrlKey(url);
            if (key.isBlank() || byUrl.containsKey(key)) {
                continue;
            }
            Citation citation = new Citation(
                    // index 占位 0,稍后由 buildCitationMap 统一重新编号
                    0,
                    // 保留原始引用源里的 index 作为 originalIndex,便于追溯
                    item.path("index").isInt() ? item.path("index").asInt() : 0,
                    truncate(text(item, "title"), MAX_TITLE_LENGTH),
                    url.trim(),
                    todo.getTodoId() == null ? "" : todo.getTodoId(),
                    // 缺失视为 true(向后兼容未带相关性元数据的工具输出)
                    item.path("entityMatch").isMissingNode() || item.path("entityMatch").asBoolean(true),
                    item.path("relevanceJudged").isMissingNode() ? Boolean.TRUE : item.path("relevanceJudged").asBoolean(),
                    text(item, "relevanceWarning")
            );
            byUrl.put(key, citation);
        }
    }

    /** 安全读取指定字段的文本值,null/null 节点返回空串。返回值已 trim。 */
    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    /**
     * 按字段名顺序取第一个非空文本值。
     * 用于兼容多种命名(如 todoId / todo_id / id)。
     */
    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    /**
     * 把 URL 归一化为去重 key。
     *
     * <p>归一化规则:</p>
     * <ul>
     *   <li>去掉 {@code #fragment} 部分(同一资源的不同锚点视为同一引用)。</li>
     *   <li>去掉末尾多余的斜杠(保留单根 {@code "/"})。</li>
     *   <li>转小写(避免大小写差异导致重复)。</li>
     * </ul>
     *
     * <p>注意不去除 query string,因为搜索关键词等可能改变实际内容。</p>
     */
    private String normalizeUrlKey(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String normalized = url.trim();
        int fragment = normalized.indexOf('#');
        if (fragment >= 0) {
            normalized = normalized.substring(0, fragment);
        }
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    /** 字符串截断,null 转空串。 */
    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength);
    }

    /**
     * 引用表:一组按全局编号排序的 {@link Citation}。
     *
     * @param citations 引用列表,index 字段为全局编号(从 1 开始)
     */
    public record CitationMap(List<Citation> citations) {
        /** 返回空引用表,用于 null safe 兜底。 */
        public static CitationMap empty() {
            return new CitationMap(List.of());
        }

        /** 判断引用表是否为空。 */
        public boolean isEmpty() {
            return citations == null || citations.isEmpty();
        }

        /**
         * 按全局 index 查找引用条目。
         * 主要供 {@link AgentFinalAnswerParser} 校验 [N] 引用编号时使用。
         *
         * @param index 全局编号(从 1 开始)
         * @return 匹配的 Citation,找不到时返回 null
         */
        public Citation byIndex(int index) {
            if (citations == null) {
                return null;
            }
            for (Citation citation : citations) {
                if (citation.index() == index) {
                    return citation;
                }
            }
            return null;
        }
    }

    /**
     * 单条引用条目。
     *
     * @param index            全局编号(从 1 开始,由 buildCitationMap 重新分配)
     * @param originalIndex    工具输出里的原始 index(用于追溯)
     * @param title            页面标题(可能被截断到 {@link #MAX_TITLE_LENGTH})
     * @param url              页面 URL
     * @param sourceTodoId     产生本引用的 Todo ID
     * @param entityMatch      引用内容是否包含目标实体(false 表示相关性可疑)
     * @param relevanceJudged  引用是否经过相关性裁判(false 表示 fail-open,默认通过)
     * @param relevanceWarning 相关性警告文本(非空表示有警告)
     */
    public record Citation(
            int index,
            int originalIndex,
            String title,
            String url,
            String sourceTodoId,
            boolean entityMatch,
            boolean relevanceJudged,
            String relevanceWarning
    ) {
        /**
         * 生成带新 index 的副本,其他字段保持不变。
         * 用于 {@link #buildCitationMap} 统一重新编号阶段。
         */
        Citation withIndex(int newIndex) {
            return new Citation(
                    newIndex,
                    originalIndex,
                    title == null ? "" : title,
                    url == null ? "" : url,
                    sourceTodoId == null ? "" : sourceTodoId,
                    entityMatch,
                    relevanceJudged,
                    relevanceWarning == null ? "" : relevanceWarning
            );
        }

        /**
         * 判断引用是否存在质量风险:
         * 实体不匹配、未经过裁判,或裁判产生了警告,任一为真即视为有风险。
         * 用于 {@link #buildPromptBlock} 给 LLM 提示"该引用谨慎使用"。
         */
        boolean hasQualityRisk() {
            return !entityMatch || !relevanceJudged || (relevanceWarning != null && !relevanceWarning.isBlank());
        }

        /**
         * 序列化为 snapshot 用的字段顺序 Map。
         * 使用 LinkedHashMap 保留字段顺序,便于 snapshot 的可读性。
         */
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("index", index);
            map.put("originalIndex", originalIndex);
            map.put("title", title == null ? "" : title);
            map.put("url", url == null ? "" : url);
            map.put("sourceTodoId", sourceTodoId == null ? "" : sourceTodoId);
            map.put("entityMatch", entityMatch);
            map.put("relevanceJudged", relevanceJudged);
            map.put("relevanceWarning", relevanceWarning == null ? "" : relevanceWarning);
            return map;
        }
    }
}
