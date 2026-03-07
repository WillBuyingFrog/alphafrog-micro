package world.willfrog.agent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.config.AgentLlmProperties;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * AgentPromptService 的 Prompt 完全静态化测试。
 *
 * <p>验证系统 Prompt 不包含任何动态内容（如日期），使 System Prompt
 * 在不同请求间保持字节级相同，最大化 LLM provider 的 Prompt Caching 命中率。</p>
 *
 * <p>动态上下文（日期）应通过 {@code dynamicContextPrefix()} 注入到 User Message。</p>
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
    void composeSystemPrompt_globalPromptShouldBeAtBeginning() {
        String prompt = promptService.agentRunSystemPrompt();
        assertTrue(prompt.startsWith(GLOBAL_PROMPT),
                "系统 Prompt 应以全局指令开头（静态前缀），当前: " + prompt.substring(0, Math.min(prompt.length(), 50)));
    }

    @Test
    void composeSystemPrompt_shouldNotContainDate() {
        String prompt = promptService.agentRunSystemPrompt();
        String todayLine = "今天是" + LocalDate.now().format(CN_DATE_FORMATTER) + "。";
        assertFalse(prompt.contains(todayLine),
                "System Prompt 不应包含日期（日期应通过 dynamicContextPrefix 注入到 User Message）");
    }

    @Test
    void composeSystemPrompt_shouldBeFullyStatic() {
        String prompt = promptService.agentRunSystemPrompt();
        assertEquals(GLOBAL_PROMPT, prompt,
                "仅含全局指令时，System Prompt 应完全等于全局指令（无动态后缀）");
    }

    @Test
    void todoPlannerPrompt_staticPrefixShouldBeStable() {
        String prompt1 = promptService.todoPlannerSystemPrompt("searchIndex, queryFund", 5);
        String prompt2 = promptService.todoPlannerSystemPrompt("searchIndex, queryFund", 5);
        assertEquals(prompt1, prompt2, "相同参数应产生完全相同的系统 Prompt");
    }

    @Test
    void todoPlannerPrompt_globalPrefixShouldBeStable() {
        String prompt1 = promptService.todoPlannerSystemPrompt("searchIndex, queryFund", 5);
        String prompt2 = promptService.todoPlannerSystemPrompt("differentTool", 10);
        assertTrue(prompt1.startsWith(GLOBAL_PROMPT), "全局指令应在 Prompt 开头");
        assertTrue(prompt2.startsWith(GLOBAL_PROMPT), "全局指令应在 Prompt 开头");
        String prefix1 = prompt1.substring(0, GLOBAL_PROMPT.length());
        String prefix2 = prompt2.substring(0, GLOBAL_PROMPT.length());
        assertEquals(prefix1, prefix2, "不同动态参数下，全局指令前缀应字节级相同");
    }

    @Test
    void todoPlannerPrompt_shouldNotContainDate() {
        String prompt = promptService.todoPlannerSystemPrompt("searchIndex", 5);
        String todayLine = "今天是" + LocalDate.now().format(CN_DATE_FORMATTER) + "。";
        assertFalse(prompt.contains(todayLine),
                "todo planner 的 System Prompt 不应包含日期");
    }

    @Test
    void dynamicContextPrefix_shouldContainTodayDate() {
        String prefix = promptService.dynamicContextPrefix();
        String todayLine = "今天是" + LocalDate.now().format(CN_DATE_FORMATTER) + "。";
        assertEquals(todayLine, prefix, "动态上下文前缀应包含今天日期");
    }
}
