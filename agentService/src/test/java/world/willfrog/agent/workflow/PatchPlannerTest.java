package world.willfrog.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.service.AgentPromptService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@ExtendWith(MockitoExtension.class)
class PatchPlannerTest {

    @Mock
    private AgentPromptService promptService;

    private PatchPlanner patchPlanner;

    @BeforeEach
    void setUp() {
        patchPlanner = new PatchPlanner(promptService, new ObjectMapper());
    }

    @Test
    void parsePatch_validInsertPatch() {
        String text = """
                {
                  "patchType": "INSERT",
                  "targetTodoId": "todo_1",
                  "patchData": {
                    "newTodo": {
                      "id": "todo_1_1",
                      "type": "TOOL_CALL",
                      "toolName": "searchFund",
                      "params": {"keyword": "fk"},
                      "reasoning": "补充基金查询"
                    }
                  },
                  "reason": "需要补充查询基金数据"
                }
                """;
        PlanPatch patch = patchPlanner.parsePatch(text);
        assertNotNull(patch);
        assertEquals(PatchType.INSERT, patch.getPatchType());
        assertEquals("todo_1", patch.getTargetTodoId());
        assertEquals("需要补充查询基金数据", patch.getReason());
        assertNotNull(patch.getPatchData().get("newTodo"));
    }

    @Test
    void parsePatch_validReplacePatch() {
        String text = """
                {
                  "patchType": "REPLACE",
                  "targetTodoId": "todo_2",
                  "patchData": {
                    "newParams": {"keyword": "new_kw"}
                  },
                  "reason": "修改查询条件"
                }
                """;
        PlanPatch patch = patchPlanner.parsePatch(text);
        assertNotNull(patch);
        assertEquals(PatchType.REPLACE, patch.getPatchType());
        assertEquals("todo_2", patch.getTargetTodoId());
    }

    @Test
    void parsePatch_validDeletePatch() {
        String text = """
                {
                  "patchType": "DELETE",
                  "targetTodoId": "todo_3",
                  "reason": "移除无效步骤"
                }
                """;
        PlanPatch patch = patchPlanner.parsePatch(text);
        assertNotNull(patch);
        assertEquals(PatchType.DELETE, patch.getPatchType());
        assertEquals("todo_3", patch.getTargetTodoId());
    }

    @Test
    void parsePatch_invalidPatchTypeReturnsNull() {
        String text = "{\"patchType\": \"INVALID\", \"targetTodoId\": \"todo_1\"}";
        PlanPatch patch = patchPlanner.parsePatch(text);
        assertNull(patch);
    }

    @Test
    void parsePatch_emptyReturnsNull() {
        assertNull(patchPlanner.parsePatch(""));
        assertNull(patchPlanner.parsePatch(null));
    }

    @Test
    void parsePatch_noJsonReturnsNull() {
        assertNull(patchPlanner.parsePatch("plain text without json"));
    }

    @Test
    void parsePatch_jsonWrappedInMarkdown() {
        String text = """
                ```json
                {
                  "patchType": "DELETE",
                  "targetTodoId": "todo_2",
                  "reason": "remove"
                }
                ```
                """;
        PlanPatch patch = patchPlanner.parsePatch(text);
        assertNotNull(patch);
        assertEquals(PatchType.DELETE, patch.getPatchType());
    }
}
