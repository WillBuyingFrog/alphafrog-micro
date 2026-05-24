package world.willfrog.agentlangchain.facade;

import org.apache.dubbo.config.annotation.DubboService;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLangchainDubboServiceMethodsTest {

    @Test
    void dubboServiceDoesNotEmitMethodLevelMetadata() {
        DubboService annotation = AgentLangchainDubboServiceImpl.class.getAnnotation(DubboService.class);
        assertEquals(0, annotation.methods().length,
                "Method-level Dubbo metadata makes the Nacos metadata payload exceed its 1024-byte limit");
    }

    @Test
    void allExpectedPublicServiceMethodsAreStillOverridden() {
        Set<String> overrides = Arrays.stream(AgentLangchainDubboServiceImpl.class.getDeclaredMethods())
                .filter(m -> !m.isSynthetic() && !"reject".equals(m.getName()))
                .filter(m -> m.getDeclaringClass() == AgentLangchainDubboServiceImpl.class)
                .map(java.lang.reflect.Method::getName)
                .collect(Collectors.toSet());

        for (String methodName : expectedServiceMethods()) {
            assertTrue(overrides.contains(methodName), "Missing AgentDubboService override: " + methodName);
        }
    }

    private static Set<String> expectedServiceMethods() {
        return Set.of(
                "createRun",
                "getRun",
                "updateRun",
                "listRuns",
                "listEvents",
                "deleteRun",
                "cancelRun",
                "pauseRun",
                "resumeRun",
                "getResult",
                "getStatus",
                "listTools",
                "listArtifacts",
                "downloadArtifact",
                "getConfig",
                "listModels",
                "getCredits",
                "applyCredits",
                "submitFeedback",
                "exportRun",
                "sendMessage",
                "listMessages",
                "getSnapshotPartsMeta",
                "getSnapshotPart");
    }
}
