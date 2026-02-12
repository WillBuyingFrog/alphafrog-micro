package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.entity.AgentRunEvent;
import world.willfrog.agent.mapper.AgentRunEventMapper;
import world.willfrog.agent.mapper.AgentRunMapper;
import world.willfrog.alphafrogmicro.common.dao.agent.AgentCreditApplicationDao;
import world.willfrog.alphafrogmicro.common.dao.user.UserDao;
import world.willfrog.alphafrogmicro.common.pojo.user.User;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentCreditServiceTest {

    @Mock
    private UserDao userDao;
    @Mock
    private AgentCreditApplicationDao creditApplicationDao;
    @Mock
    private AgentRunMapper runMapper;
    @Mock
    private AgentRunEventMapper eventMapper;
    @Mock
    private AgentModelCatalogService modelCatalogService;

    private AgentCreditService service;

    @BeforeEach
    void setUp() {
        service = new AgentCreditService(
                userDao,
                creditApplicationDao,
                runMapper,
                eventMapper,
                modelCatalogService,
                new ObjectMapper()
        );
        ReflectionTestUtils.setField(service, "defaultToolCost", 1);
        ReflectionTestUtils.setField(service, "executePythonCost", 5);
    }

    @Test
    void applyCredits_shouldIncreaseAndPersistApplication() {
        User before = new User();
        before.setUserId(1L);
        before.setCredit(100);
        User after = new User();
        after.setUserId(1L);
        after.setCredit(1100);

        when(userDao.getUserById(1L)).thenReturn(before, after);
        when(userDao.increaseCreditByUserId(1L, 1000)).thenReturn(1);
        when(runMapper.sumCompletedCreditsByUser("1")).thenReturn(200);

        AgentCreditService.ApplyCreditSummary summary = service.applyCredits("1", 1000, "test", "u@example.com");

        assertNotNull(summary.applicationId());
        assertEquals(1100, summary.totalCredits());
        assertEquals(900, summary.remainingCredits());
        assertEquals(200, summary.usedCredits());
        assertEquals("APPROVED", summary.status());

        ArgumentCaptor<world.willfrog.alphafrogmicro.common.pojo.agent.AgentCreditApplication> captor =
                ArgumentCaptor.forClass(world.willfrog.alphafrogmicro.common.pojo.agent.AgentCreditApplication.class);
        verify(creditApplicationDao).insert(captor.capture());
        assertEquals(1000, captor.getValue().getAmount());
    }

    @Test
    void calculateRunTotalCredits_shouldIncludeLlmAndToolCredits() {
        AgentRun run = new AgentRun();
        run.setExt("{\"model_name\":\"openai/gpt-5.2\"}");
        run.setSnapshotJson("{\"observability\":{\"summary\":{\"totalTokens\":1200}}}");

        AgentRunEvent e1 = new AgentRunEvent();
        e1.setEventType("TOOL_CALL_FINISHED");
        e1.setPayloadJson("{\"tool_name\":\"executePython\",\"cacheHit\":false}");
        AgentRunEvent e2 = new AgentRunEvent();
        e2.setEventType("TOOL_CALL_FINISHED");
        e2.setPayloadJson("{\"tool_name\":\"searchStock\",\"cacheHit\":true}");

        when(modelCatalogService.resolveBaseRate("openai/gpt-5.2")).thenReturn(1.5D);

        int total = service.calculateRunTotalCredits(run, List.of(e1, e2), "");
        assertEquals(7, total);
    }
}
