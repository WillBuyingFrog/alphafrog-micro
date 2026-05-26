package world.willfrog.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 最终回答解析器,兼容三种 LLM 输出格式,同时校验引用编号合法性。
 *
 * <h3>支持的输入格式</h3>
 * <ol>
 *   <li><b>纯 Markdown 文本</b>(最常见):直接用作 answer_markdown,structuredAnswer 为 null。</li>
 *   <li><b>裸 JSON 对象</b>:以 {@code "{"} 开头、{@code "}"} 结尾,解析为 structuredAnswer,
 *       并从中读取 {@code answer_markdown}/{@code markdown}/{@code answer}/{@code content}
 *       任一字段作为可展示文本。</li>
 *   <li><b>Fenced ```json``` 代码块</b>:与裸 JSON 处理流程一致,只是需要先剥离围栏。</li>
 * </ol>
 *
 * <h3>返回值</h3>
 * 解析结果封装在 {@link ParsedAnswer} record 中,包含:
 * <ul>
 *   <li>{@code answerRaw} — LLM 输出的原始文本(未做格式调整)</li>
 *   <li>{@code answerMarkdown} — 可直接展示的 Markdown 文本</li>
 *   <li>{@code structuredAnswer} — 结构化字段(若 LLM 输出为 JSON 时非 null)</li>
 *   <li>{@code qualityFlags} — 质量标记列表,见下</li>
 * </ul>
 *
 * <h3>引用编号校验</h3>
 * 解析时会扫描文本中所有形如 {@code [N]} 的引用编号,与 {@link AgentCitationService.CitationMap}
 * 对照,生成以下质量标记:
 * <ul>
 *   <li>{@code EMPTY_ANSWER} — LLM 输出为空或空白</li>
 *   <li>{@code JSON_PARSE_FALLBACK} — 看起来是 JSON 但解析失败,已回退为纯文本</li>
 *   <li>{@code CITATION_REFERENCE_WITHOUT_MAP} — 文本中有 [N] 但引用表为空</li>
 *   <li>{@code CITATION_REFERENCE_OUT_OF_RANGE} — [N] 超出了引用表范围(无效编号)</li>
 *   <li>{@code CITATION_REFERENCE_JUDGE_FAIL_OPEN} — 引用未经过相关性裁判(open 默认通过)</li>
 *   <li>{@code CITATION_REFERENCE_LOW_RELEVANCE} — 引用实体不匹配或有相关性警告</li>
 * </ul>
 *
 * @see AgentCitationService
 */
@Service
@RequiredArgsConstructor
public class AgentFinalAnswerParser {

    /**
     * 引用编号正则:匹配独立的 [N] 序列,N 为正整数。
     * <p>前置条件:不能紧跟 {@code !}(避免误匹配 Markdown 图片语法 {@code ![desc]}),
     * 不能紧跟单词字符(避免匹配链接锚点等)。</p>
     */
    private static final Pattern CITATION_REF = Pattern.compile("(?<![!\\w])\\[\\s*([1-9]\\d*)\\s*]");

    private final ObjectMapper objectMapper;

    /**
     * 不带引用表的解析,等价于使用空引用表。
     * 主要供单元测试或不关心引用校验的场景使用。
     *
     * @param raw LLM 原始输出
     * @return 解析结果
     */
    public ParsedAnswer parse(String raw) {
        return parse(raw, AgentCitationService.CitationMap.empty());
    }

    /**
     * 主入口:解析 LLM 输出文本为 {@link ParsedAnswer},并基于引用表校验编号。
     *
     * <h4>处理流程</h4>
     * <ol>
     *   <li>归一化:trim 空白,空输入直接返回 EMPTY_ANSWER 标记。</li>
     *   <li>尝试提取 JSON candidate(裸 JSON 或 fenced ```json``` 代码块)。</li>
     *   <li>JSON 解析:
     *     <ul>
     *       <li>成功 → 提取 answer_markdown 等字段,记录 quality_flags,再校验 [N] 引用。</li>
     *       <li>失败 → 回退为纯文本,加 JSON_PARSE_FALLBACK 标记。</li>
     *     </ul>
     *   </li>
     *   <li>非 JSON 输入:直接当作 Markdown,只做引用编号校验。</li>
     * </ol>
     *
     * @param raw         LLM 原始输出文本,允许为 null
     * @param citationMap 引用表,用于校验 [N] 编号是否合法,允许为 null/empty
     * @return 解析结果(永不为 null)
     */
    public ParsedAnswer parse(String raw, AgentCitationService.CitationMap citationMap) {
        // 归一化空白,记录空输入标记
        String normalizedRaw = raw == null ? "" : raw.trim();
        if (normalizedRaw.isBlank()) {
            return new ParsedAnswer("", "", null, List.of("EMPTY_ANSWER"));
        }

        // 尝试将输入识别为 JSON candidate(裸 JSON 或 fenced 代码块)
        String jsonCandidate = extractJsonCandidate(normalizedRaw);
        if (jsonCandidate != null) {
            try {
                JsonNode root = objectMapper.readTree(jsonCandidate);
                if (root != null && root.isObject()) {
                    // 将 JSON 树转为通用 Map,作为 structuredAnswer 保留所有字段
                    Map<String, Object> structured = objectMapper.convertValue(
                            root, new TypeReference<Map<String, Object>>() {
                            });
                    // 按优先级从多个候选字段中取第一个非空的作为可展示 Markdown
                    String markdown = firstText(root, "answer_markdown", "markdown", "answer", "content");
                    if (markdown.isBlank()) {
                        // 找不到任何标准字段则回退到原始文本
                        markdown = normalizedRaw;
                    }
                    // LLM 可能自带 quality_flags(模型自我评估),合并进来
                    List<String> flags = readQualityFlags(root);
                    flags = withCitationFlags(markdown, citationMap, flags);
                    return new ParsedAnswer(normalizedRaw, markdown.trim(), structured, flags);
                }
            } catch (Exception ignored) {
                // JSON 解析失败:可能是模型输出了非法 JSON,降级为纯文本并加标记
                return new ParsedAnswer(normalizedRaw, normalizedRaw, null,
                        withCitationFlags(normalizedRaw, citationMap, List.of("JSON_PARSE_FALLBACK")));
            }
        }

        // 非 JSON 输入:直接作为 Markdown,仅校验引用编号
        return new ParsedAnswer(normalizedRaw, normalizedRaw, null,
                withCitationFlags(normalizedRaw, citationMap, List.of()));
    }

    /**
     * 将结构化答案 Map 序列化为 JSON 字符串。
     *
     * <p>用于 snapshot 存档时把 structured_answer 字段写成 JSON 文本。
     * 序列化失败或空 Map 时返回空串。</p>
     *
     * @param structuredAnswer 结构化答案 Map
     * @return JSON 字符串或空串
     */
    public String writeStructuredJson(Map<String, Object> structuredAnswer) {
        if (structuredAnswer == null || structuredAnswer.isEmpty()) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(structuredAnswer);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 从原始文本中提取 JSON candidate。
     *
     * <p>顺序:</p>
     * <ol>
     *   <li>若是 ```...``` 围栏块,剥离围栏后判断是否是 JSON 对象。</li>
     *   <li>否则若直接以 {@code "{"} 开头、{@code "}"} 结尾,认为是裸 JSON。</li>
     *   <li>都不匹配返回 null。</li>
     * </ol>
     *
     * @return JSON 文本片段,或 null 表示输入不是 JSON 形态
     */
    private String extractJsonCandidate(String raw) {
        String fenced = extractFencedContent(raw);
        if (fenced != null) {
            String trimmed = fenced.trim();
            // 围栏块内容也必须是对象形式,否则不视为 JSON 对象
            return trimmed.startsWith("{") && trimmed.endsWith("}") ? trimmed : null;
        }
        if (raw.startsWith("{") && raw.endsWith("}")) {
            return raw;
        }
        return null;
    }

    /**
     * 剥离 fenced 代码块的围栏标记。
     *
     * <p>仅识别空标签的围栏(```\n...\n```)或显式 json 标签(```json\n...\n```),
     * 其他语言标签视为非 JSON 围栏并返回 null。</p>
     *
     * @return 围栏内的内容,或 null 表示输入不是合法的 JSON fenced 块
     */
    private String extractFencedContent(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (!trimmed.startsWith("```") || !trimmed.endsWith("```")) {
            return null;
        }
        int firstLineEnd = trimmed.indexOf('\n');
        if (firstLineEnd < 0) {
            // 单行 ``` ... ``` 不视为 fenced 块
            return null;
        }
        String fenceHeader = trimmed.substring(3, firstLineEnd).trim();
        // 仅接受无标签或 json 标签
        if (!fenceHeader.isBlank() && !"json".equalsIgnoreCase(fenceHeader)) {
            return null;
        }
        return trimmed.substring(firstLineEnd + 1, trimmed.length() - 3).trim();
    }

    /**
     * 按候选字段名顺序读取第一个非空文本节点。
     * 用于在多种命名习惯(answer_markdown / markdown / answer / content)间寻找可展示内容。
     *
     * @param root  JSON 根节点
     * @param names 候选字段名,按优先级排列
     * @return 第一个非空文本值,全部缺失时返回空串
     */
    private String firstText(JsonNode root, String... names) {
        for (String name : names) {
            JsonNode node = root.get(name);
            if (node != null && node.isTextual() && !node.asText("").isBlank()) {
                return node.asText("");
            }
        }
        return "";
    }

    /**
     * 从 JSON 中读取 LLM 自带的 quality_flags 字段。
     *
     * <p>兼容两种命名:{@code quality_flags}(snake_case)和 {@code qualityFlags}(camelCase)。
     * 兼容两种类型:数组或单个字符串。</p>
     *
     * @return 标记列表,无则返回空 List
     */
    private List<String> readQualityFlags(JsonNode root) {
        JsonNode node = root.get("quality_flags");
        if (node == null) {
            node = root.get("qualityFlags");
        }
        if (node == null || node.isNull()) {
            return List.of();
        }
        List<String> flags = new ArrayList<>();
        if (node.isArray()) {
            // 数组:逐项加入,跳过空白项
            for (JsonNode item : node) {
                String flag = item.asText("");
                if (!flag.isBlank()) {
                    flags.add(flag);
                }
            }
        } else if (node.isTextual() && !node.asText("").isBlank()) {
            // 单字符串:作为唯一标记加入
            flags.add(node.asText(""));
        }
        return flags;
    }

    /**
     * 在已有标记基础上叠加引用编号校验标记。
     *
     * <h4>校验规则</h4>
     * <ul>
     *   <li>文本无 [N] 引用 → 不新增任何标记。</li>
     *   <li>有 [N] 但引用表为空 → 加 {@code CITATION_REFERENCE_WITHOUT_MAP}。</li>
     *   <li>[N] 编号在引用表中找不到 → 加 {@code CITATION_REFERENCE_OUT_OF_RANGE}。</li>
     *   <li>对应引用未经过相关性裁判 → 加 {@code CITATION_REFERENCE_JUDGE_FAIL_OPEN}。</li>
     *   <li>对应引用实体不匹配或有警告 → 加 {@code CITATION_REFERENCE_LOW_RELEVANCE}。</li>
     * </ul>
     *
     * <p>使用 {@link LinkedHashSet} 保证标记唯一且保留插入顺序。</p>
     *
     * @param markdown      最终展示文本
     * @param citationMap   引用表(可为 null)
     * @param existingFlags 已有标记
     * @return 合并去重后的标记列表
     */
    private List<String> withCitationFlags(String markdown,
                                           AgentCitationService.CitationMap citationMap,
                                           List<String> existingFlags) {
        Set<String> flags = new LinkedHashSet<>();
        if (existingFlags != null) {
            flags.addAll(existingFlags);
        }
        Set<Integer> references = extractCitationReferences(markdown);
        if (references.isEmpty()) {
            return new ArrayList<>(flags);
        }
        // 文本中存在 [N] 但缺少引用表,记录可疑情况
        if (citationMap == null || citationMap.isEmpty()) {
            flags.add("CITATION_REFERENCE_WITHOUT_MAP");
            return new ArrayList<>(flags);
        }
        // 逐个核对引用编号是否在引用表范围内、相关性是否充分
        for (Integer reference : references) {
            AgentCitationService.Citation citation = citationMap.byIndex(reference);
            if (citation == null) {
                flags.add("CITATION_REFERENCE_OUT_OF_RANGE");
                continue;
            }
            // 未经过相关性裁判 → 标记为 fail-open(默认通过但不保证质量)
            if (!citation.relevanceJudged()) {
                flags.add("CITATION_REFERENCE_JUDGE_FAIL_OPEN");
            }
            // 实体不匹配或裁判产生了警告 → 标记为低相关性
            if (!citation.entityMatch() || !nvl(citation.relevanceWarning()).isBlank()) {
                flags.add("CITATION_REFERENCE_LOW_RELEVANCE");
            }
        }
        return new ArrayList<>(flags);
    }

    /**
     * 扫描文本提取所有 [N] 引用编号。
     *
     * @param markdown Markdown 文本
     * @return 去重后的引用编号集合,保留首次出现顺序
     */
    private Set<Integer> extractCitationReferences(String markdown) {
        Set<Integer> references = new LinkedHashSet<>();
        if (markdown == null || markdown.isBlank()) {
            return references;
        }
        Matcher matcher = CITATION_REF.matcher(markdown);
        while (matcher.find()) {
            try {
                references.add(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                // 正则已限制为数字；此处仅防御极端溢出。
            }
        }
        return references;
    }

    /** 空安全:null 转为空串。 */
    private String nvl(String value) {
        return value == null ? "" : value;
    }

    /**
     * 解析后的最终答案。
     *
     * @param answerRaw        LLM 原始输出文本(未做格式调整)
     * @param answerMarkdown   可直接展示的 Markdown 文本
     * @param structuredAnswer 结构化字段(JSON 输入时非 null,纯 Markdown 输入时为 null)
     * @param qualityFlags     质量标记列表,见类级 Javadoc
     */
    public record ParsedAnswer(
            String answerRaw,
            String answerMarkdown,
            Map<String, Object> structuredAnswer,
            List<String> qualityFlags
    ) {
        /**
         * 把本 record 摊平为 snapshot JSON 中的扁平字段。
         *
         * <p>调用方一般是 {@code AgentRunExecutor#buildSnapshotJson},
         * 把这些字段并入 snapshot 的顶层。所有 null 字段会被替换为空值,
         * 保证 snapshot 字段结构稳定。</p>
         *
         * @return 包含 answer_raw / answer_markdown / structured_answer / quality_flags 的 Map
         */
        public Map<String, Object> toSnapshotFields() {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("answer_raw", answerRaw == null ? "" : answerRaw);
            fields.put("answer_markdown", answerMarkdown == null ? "" : answerMarkdown);
            fields.put("structured_answer", structuredAnswer == null ? Map.of() : structuredAnswer);
            fields.put("quality_flags", qualityFlags == null ? List.of() : qualityFlags);
            return fields;
        }
    }
}
