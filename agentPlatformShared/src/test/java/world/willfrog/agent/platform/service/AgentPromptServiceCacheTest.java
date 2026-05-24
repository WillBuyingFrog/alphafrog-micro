package world.willfrog.agent.platform.service;

import world.willfrog.agent.platform.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.platform.config.AgentLlmProperties;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * AgentPromptService 的 system prompt 组装与时间基准测试。
 */
@ExtendWith(MockitoExtension.class)
class AgentPromptServiceCacheTest {

    private static final String GLOBAL_PROMPT = "你是一个专业的金融分析助手。";
    private static final DateTimeFormatter CN_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy年MM月dd日");

    @Mock
    private AgentLlmLocalConfigLoader localConfigLoader;

    private AgentPromptService promptService;

    @BeforeEach
    void setUp() {
        AgentLlmProperties properties = new AgentLlmProperties();
        AgentLlmProperties.Prompts prompts = new AgentLlmProperties.Prompts();
        prompts.setAgentRunSystemPrompt(GLOBAL_PROMPT);
        properties.setPrompts(prompts);
        lenient().when(localConfigLoader.current()).thenReturn(Optional.empty());
        promptService = new AgentPromptService(properties, localConfigLoader);
    }

    @Test
    void composeSystemPrompt_shouldInjectTimeBaselineBeforeGlobal() {
        String prompt = promptService.agentRunSystemPrompt();
        LocalDate today = LocalDate.now();
        assertTrue(prompt.startsWith("当前时间：" + today.format(CN_DATE_FORMATTER)),
                "系统 Prompt 应以时间基准前缀开头");
        assertTrue(prompt.contains(GLOBAL_PROMPT), "时间基准后应拼接全局指令");
    }

    @Test
    void composeSystemPrompt_shouldMapRelativeYearsFromToday() {
        String prompt = promptService.agentRunSystemPrompt();
        int thisYear = LocalDate.now().getYear();
        assertTrue(prompt.contains("说去年，指" + (thisYear - 1) + "年"),
                "时间基准应包含由当前年推算的去年示例");
        assertTrue(prompt.contains("说再上一年，指" + (thisYear - 2) + "年"),
                "时间基准应包含由当前年推算的再上一年示例");
    }

    @Test
    void todoPlannerPrompt_staticPrefixShouldBeStable() {
        String prompt1 = promptService.todoPlannerSystemPrompt("searchIndex, queryFund", 5);
        String prompt2 = promptService.todoPlannerSystemPrompt("searchIndex, queryFund", 5);
        assertEquals(prompt1, prompt2, "相同参数应产生完全相同的系统 Prompt");
    }

    @Test
    void todoPlannerPrompt_timeBaselinePrefixShouldBeStableAcrossTemplates() {
        String prompt1 = promptService.todoPlannerSystemPrompt("searchIndex, queryFund", 5);
        String prompt2 = promptService.todoPlannerSystemPrompt("differentTool", 10);
        assertTrue(prompt1.startsWith("当前时间：" + LocalDate.now().format(CN_DATE_FORMATTER)),
                "todo planner 应以时间基准前缀开头");
        int globalAt1 = prompt1.indexOf(GLOBAL_PROMPT);
        int globalAt2 = prompt2.indexOf(GLOBAL_PROMPT);
        assertTrue(globalAt1 > 0 && globalAt2 > 0);
        assertEquals(prompt1.substring(0, globalAt1), prompt2.substring(0, globalAt2),
                "不同模板下，时间基准前缀应字节级一致");
    }

    @Test
    void dynamicContextPrefix_shouldContainTodayDateOnly() {
        String prefix = promptService.dynamicContextPrefix();
        LocalDate today = LocalDate.now();
        String todayLine = "今天是" + today.format(CN_DATE_FORMATTER) + "。";
        assertEquals(todayLine, prefix, "动态上下文前缀应仅包含今天日期，不做短语级硬编码替换");
    }

    // ─── ReAct 统一 System Prompt + Stage Instruction 测试 ───

    @Test
    void reactSystemPrompt_shouldReturnGlobalWithTimeBaseline() {
        String reactSys = promptService.reactSystemPrompt();
        assertTrue(reactSys.startsWith("当前时间：" + LocalDate.now().format(CN_DATE_FORMATTER)),
                "reactSystemPrompt 应含时间基准前缀");
        assertTrue(reactSys.contains(GLOBAL_PROMPT),
                "reactSystemPrompt 应包含全局指令，不包含 stage-specific 内容");
    }

    @Test
    void reactSystemPrompt_shouldBeIdenticalAcrossStages() {
        // 验证 reactSystemPrompt 在不同阶段调用时返回完全一致的结果
        String r1 = promptService.reactSystemPrompt();
        String r2 = promptService.reactSystemPrompt();
        assertEquals(r1, r2, "reactSystemPrompt 在不同时间调用应返回字节级一致的结果");
    }

    @Test
    void planningAnalysisStageInstruction_shouldContainStageMarker() {
        String instruction = promptService.planningAnalysisStageInstruction("searchIndex, queryFund", 5);
        assertTrue(instruction.contains("[Stage: PLANNING_ANALYSIS]"),
                "planning analysis 阶段指令应包含 Stage 标记");
    }

    @Test
    void planningStrategyStageInstruction_shouldDescribeMarketDataBatchSyntax() {
        AgentLlmProperties properties = new AgentLlmProperties();
        AgentLlmProperties.Prompts prompts = new AgentLlmProperties.Prompts();
        prompts.setPlanningStrategyStage("{{toolCapabilities}}");
        properties.setPrompts(prompts);
        AgentPromptService service = new AgentPromptService(properties, localConfigLoader);

        String instruction = service.planningStrategyStageInstruction(
                "searchIndex,getIndexDaily,searchStock,getStockDaily",
                5,
                500
        );

        assertTrue(instruction.contains("keyword=\"沪深300|中证500\""),
                "规划阶段应提示 search 类工具使用 | 批量关键词");
        assertTrue(instruction.contains("tsCode=\"000300.SH|000905.SH\""),
                "规划阶段应提示日线工具使用 | 批量代码");
        assertTrue(instruction.contains("JSON 数组"),
                "规划阶段应提示可使用 JSON 数组批量参数");
        assertFalse(instruction.contains("ts_code 用逗号分隔"),
                "行情批量参数不应提示使用逗号分隔");
    }

    @Test
    void planningStructuredStageInstruction_shouldContainStageMarker() {
        String instruction = promptService.planningStructuredStageInstruction();
        assertTrue(instruction.contains("[Stage: PLANNING_STRUCTURED]"),
                "planning structured 阶段指令应包含 Stage 标记");
    }

    @Test
    void recoveryStageInstruction_shouldContainStageMarker() {
        String instruction = promptService.recoveryStageInstruction();
        assertTrue(instruction.contains("[Stage: TODO_RECOVERY]"),
                "recovery 阶段指令应包含 Stage 标记");
    }

    @Test
    void finalAnswerStageInstruction_shouldContainStageMarker() {
        String instruction = promptService.finalAnswerStageInstruction();
        assertTrue(instruction.contains("[Stage: FINAL_ANSWER]"),
                "final answer 阶段指令应包含 Stage 标记");
    }

    @Test
    void dagReactSystemPrompt_shouldSuggestSoftBatchGuidance() {
        String prompt = promptService.dagReactSystemPrompt();
        assertTrue(prompt.contains("批量查询（建议，非强制）"),
                "ReAct system prompt 应包含软性的批量查询建议");
        assertTrue(prompt.contains("发现式查询"),
                "ReAct system prompt 应保留发现式逐步查询的边界说明");
        assertTrue(prompt.contains("searchAssetInfo"),
                "ReAct system prompt 应列举支持批量的工具名");
        assertTrue(prompt.contains("单次最多 3 个查询"),
                "ReAct system prompt 应标明搜索类工具批量上限");
        assertTrue(prompt.contains("单次最多 2 个查询"),
                "ReAct system prompt 应标明日线类工具批量上限");
    }
}
