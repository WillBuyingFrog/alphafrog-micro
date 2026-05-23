package world.willfrog.agent.platform.service;

import world.willfrog.agent.platform.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import world.willfrog.agent.platform.config.AgentSnapshotProperties;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SnapshotPartServiceTest {

    @Mock
    private RedisTemplate<String, byte[]> snapshotPartRedisTemplate;
    @Mock
    private ValueOperations<String, byte[]> valueOperations;

    private SnapshotPartService createService(AgentSnapshotProperties properties) {
        lenient().when(snapshotPartRedisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.get(anyString())).thenReturn(null);
        return new SnapshotPartService(properties, snapshotPartRedisTemplate, new ObjectMapper());
    }

    @Test
    void resolvePartSize_shouldClampToConfiguredBounds() {
        AgentSnapshotProperties properties = new AgentSnapshotProperties();
        properties.setDefaultPartSize(128);
        properties.setMinPartSize(64);
        properties.setMaxPartSize(512);
        SnapshotPartService service = createService(properties);

        assertEquals(128, service.resolvePartSize(0));
        assertEquals(64, service.resolvePartSize(32));
        assertEquals(512, service.resolvePartSize(9999));
    }

    @Test
    void getOrBuildMeta_shouldSplitCompressedSnapshotIntoParts() {
        AgentSnapshotProperties properties = baseProperties();
        SnapshotPartService service = createService(properties);
        String snapshot = "{\"hello\":\"world\"}";

        SnapshotPartsMeta meta = service.getOrBuildMeta("run-1", snapshot, 0);

        assertEquals("run-1", meta.getRunId());
        assertEquals(128, meta.getPartSize());
        assertTrue(meta.getTotalParts() >= 1);
        assertEquals(snapshot.getBytes(StandardCharsets.UTF_8).length, meta.getUncompressedSize());
        assertTrue(meta.getCompressedSize() > 0);
        assertEquals("gzip", meta.getCompression());
        assertEquals(32, meta.getChecksum().length());

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, org.mockito.Mockito.atLeastOnce()).set(keyCaptor.capture(), any(byte[].class));
        assertTrue(keyCaptor.getAllValues().stream().anyMatch(key -> key.contains(":snapshot:parts:128:meta")));
    }

    @Test
    void getPartBytes_shouldRoundTripAllParts() throws Exception {
        AgentSnapshotProperties properties = baseProperties();
        SnapshotPartService service = createService(properties);
        StringBuilder builder = new StringBuilder("{\"payload\":\"");
        for (int i = 0; i < 300; i++) {
            builder.append('a' + (i % 26));
        }
        builder.append("\"}");
        String snapshot = builder.toString();

        SnapshotPartsMeta meta = service.getOrBuildMeta("run-2", snapshot, 32);
        assertTrue(meta.getTotalParts() >= 2);

        Map<String, byte[]> cached = new HashMap<>();
        when(valueOperations.get(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return cached.get(key);
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            cached.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(valueOperations).set(anyString(), any(byte[].class));

        service.getOrBuildMeta("run-2", snapshot, 32);

        List<byte[]> parts = new ArrayList<>();
        for (int i = 0; i < meta.getTotalParts(); i++) {
            parts.add(service.getPartBytes("run-2", snapshot, i, 32));
        }

        byte[] combined = new byte[parts.stream().mapToInt(part -> part.length).sum()];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, combined, offset, part.length);
            offset += part.length;
        }

        byte[] gunzipped;
        try (GZIPInputStream gzip = new GZIPInputStream(new java.io.ByteArrayInputStream(combined))) {
            gunzipped = gzip.readAllBytes();
        }
        assertArrayEquals(snapshot.getBytes(StandardCharsets.UTF_8), gunzipped);
    }

    @Test
    void getPartBytes_shouldRejectOutOfRangeIndex() {
        AgentSnapshotProperties properties = baseProperties();
        SnapshotPartService service = createService(properties);
        SnapshotPartsMeta meta = SnapshotPartsMeta.builder()
                .runId("run-3")
                .partSize(128)
                .totalParts(1)
                .uncompressedSize(10)
                .compressedSize(20)
                .compression("gzip")
                .checksum("abc")
                .build();
        when(valueOperations.get(eq("agent:run:run-3:snapshot:parts:128:meta"))).thenReturn(toBytes(meta));

        assertThrows(IllegalArgumentException.class,
                () -> service.getPartBytes("run-3", "{}", 1, 128));
    }

    private AgentSnapshotProperties baseProperties() {
        AgentSnapshotProperties properties = new AgentSnapshotProperties();
        properties.setDefaultPartSize(128);
        properties.setMinPartSize(64);
        properties.setMaxPartSize(512);
        properties.setCacheTtlSeconds(60);
        properties.setGzipEnabled(true);
        return properties;
    }

    private byte[] toBytes(SnapshotPartsMeta meta) {
        try {
            return new ObjectMapper().writeValueAsBytes(meta);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
