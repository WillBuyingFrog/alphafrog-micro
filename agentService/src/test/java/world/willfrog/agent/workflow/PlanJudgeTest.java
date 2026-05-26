package world.willfrog.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.platform.service.AgentPromptService;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
class PlanJudgeTest {

    @Mock
    private AgentPromptService promptService;

    private PlanJudge planJudge;

    @BeforeEach
    void setUp() {
        planJudge = new PlanJudge(promptService, new ObjectMapper());
    }

    @Test
    void parseDecision_jsonWithPatchPlan() {
        String text = "{\"decision\": \"PATCH_PLAN\", \"reason\": \"need to add query\"}";
        assertEquals(JudgeDecision.PATCH_PLAN, planJudge.parseDecision(text));
    }

    @Test
    void parseDecision_jsonWithContinue() {
        String text = "{\"decision\": \"CONTINUE\"}";
        assertEquals(JudgeDecision.CONTINUE_WITH_RECOVERY_PARAMS, planJudge.parseDecision(text));
    }

    @Test
    void parseDecision_jsonWithRetry() {
        String text = "{\"decision\": \"RETRY\"}";
        assertEquals(JudgeDecision.RETRY, planJudge.parseDecision(text));
    }

    @Test
    void parseDecision_jsonWithFail() {
        String text = "{\"decision\": \"FAIL\"}";
        assertEquals(JudgeDecision.FAIL, planJudge.parseDecision(text));
    }

    @Test
    void parseDecision_rawTextWithPatchPlan() {
        String text = "PATCH_PLAN";
        assertEquals(JudgeDecision.PATCH_PLAN, planJudge.parseDecision(text));
    }

    @Test
    void parseDecision_rawTextWithFallbackToLinear() {
        String text = "FALLBACK_TO_LINEAR";
        assertEquals(JudgeDecision.FALLBACK_TO_LINEAR, planJudge.parseDecision(text));
    }

    @Test
    void parseDecision_rawTextWithContinue() {
        String text = "CONTINUE";
        assertEquals(JudgeDecision.CONTINUE_WITH_RECOVERY_PARAMS, planJudge.parseDecision(text));
    }

    @Test
    void parseDecision_rawTextWithContinueWithRecoveryParams() {
        String text = "CONTINUE_WITH_RECOVERY_PARAMS";
        assertEquals(JudgeDecision.CONTINUE_WITH_RECOVERY_PARAMS, planJudge.parseDecision(text));
    }

    @Test
    void parseDecision_emptyDefaultsToFail() {
        assertEquals(JudgeDecision.FAIL, planJudge.parseDecision(""));
        assertEquals(JudgeDecision.FAIL, planJudge.parseDecision(null));
    }

    @Test
    void parseDecision_jsonWrappedInMarkdown() {
        String text = "```json\n{\"decision\": \"PATCH_PLAN\"}\n```";
        assertEquals(JudgeDecision.PATCH_PLAN, planJudge.parseDecision(text));
    }

    @Test
    void parseDecision_unknownDefaultsToFail() {
        String text = "UNKNOWN_VALUE";
        assertEquals(JudgeDecision.FAIL, planJudge.parseDecision(text));
    }
}
