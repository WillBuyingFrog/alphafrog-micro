package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.entity.AgentRunEvent;
import world.willfrog.agent.mapper.AgentRunEventMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentArtifactServiceTest {

    @Mock
    private AgentRunEventMapper eventMapper;

    private AgentArtifactService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        service = new AgentArtifactService(eventMapper, new ObjectMapper());
        ReflectionTestUtils.setField(service, "artifactStoragePath", tempDir.resolve("artifacts").toString());
        ReflectionTestUtils.setField(service, "datasetPath", tempDir.resolve("datasets").toString());
        ReflectionTestUtils.setField(service, "normalRetentionDays", 7);
        ReflectionTestUtils.setField(service, "adminRetentionDays", 30);
        ReflectionTestUtils.setField(service, "downloadMaxBytes", 1024L * 1024L);
    }

    @Test
    void listArtifacts_shouldParseTodoEventsAndExportPythonScript() throws Exception {
        Path datasetDir = tempDir.resolve("datasets").resolve("ds1");
        Files.createDirectories(datasetDir);
        Files.writeString(datasetDir.resolve("ds1.csv"), "a,b\n1,2\n");
        Files.writeString(datasetDir.resolve("ds1.meta.json"), "{\"id\":\"ds1\"}");

        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setUserId("u1");
        run.setStartedAt(OffsetDateTime.now());

        AgentRunEvent e1 = new AgentRunEvent();
        e1.setRunId("run-1");
        e1.setSeq(1);
        e1.setEventType("TODO_LIST_CREATED");
        e1.setPayloadJson("{\"plan\":{\"items\":[{\"id\":\"todo_1\",\"toolName\":\"executePython\",\"params\":{\"code\":\"print(1)\",\"dataset_ids\":\"ds1\"}}]}}");
        e1.setCreatedAt(OffsetDateTime.now());

        AgentRunEvent e2 = new AgentRunEvent();
        e2.setRunId("run-1");
        e2.setSeq(2);
        e2.setEventType("TODO_STARTED");
        e2.setPayloadJson("{\"todo_id\":\"todo_1\",\"tool\":\"executePython\"}");
        e2.setCreatedAt(OffsetDateTime.now());

        AgentRunEvent e3 = new AgentRunEvent();
        e3.setRunId("run-1");
        e3.setSeq(3);
        e3.setEventType("TODO_FINISHED");
        e3.setPayloadJson("{\"todo_id\":\"todo_1\",\"success\":true,\"output_preview\":\"{\\\"ok\\\":true,\\\"data\\\":{\\\"dataset_id\\\":\\\"ds1\\\"}}\"}");
        e3.setCreatedAt(OffsetDateTime.now());

        when(eventMapper.listByRunId("run-1")).thenReturn(List.of(e1, e2, e3));

        var artifacts = service.listArtifacts(run, false);

        assertTrue(artifacts.stream().anyMatch(a -> "python_script".equals(a.getType())));
        assertTrue(artifacts.stream().allMatch(a -> a.getUrl().startsWith("/api/agent/runs/run-1/artifacts/")));
    }
}
