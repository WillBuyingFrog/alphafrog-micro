package world.willfrog.agent.platform.service;

import world.willfrog.agent.platform.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.CodeRefineProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodeRefineLocalConfigLoaderTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @TempDir
    Path tempDir;

    @Test
    void loadShouldReportStateWithInstanceDimension() throws Exception {
        Path configFile = tempDir.resolve("code-refine.local.json");
        Files.writeString(configFile, "{\"maxAttempts\": 5}");

        CodeRefineProperties properties = new CodeRefineProperties();
        properties.setConfigFile(configFile.toString());

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        CodeRefineLocalConfigLoader loader = new CodeRefineLocalConfigLoader(
                new ObjectMapper(),
                redisTemplate,
                properties
        );
        ReflectionTestUtils.setField(loader, "serviceName", "agent-service");
        ReflectionTestUtils.setField(loader, "instanceId", "pod-1");

        loader.load();

        assertTrue(loader.current().isPresent());
        assertEquals(5, loader.current().orElseThrow().getMaxAttempts());
        verify(valueOperations).set(
                eq("config:state:agent-service:pod-1:code-refine.json"),
                argThat(value -> value.contains("\"md5\":\"58bfc82ac4730befd74980d129b27375\"")
                        && value.contains("\"loadedAt\":\"")),
                eq(Duration.ofSeconds(30))
        );
    }
}
