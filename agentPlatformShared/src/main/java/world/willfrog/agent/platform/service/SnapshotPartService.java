package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.config.AgentSnapshotProperties;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPOutputStream;

@Service
@Slf4j
public class SnapshotPartService {

    private static final String KEY_PREFIX = "agent:run:";
    private static final String PARTS_SUFFIX = ":snapshot:parts:";
    private static final String META_SUFFIX = ":meta";
    private static final String COMPRESSION_GZIP = "gzip";
    private static final String COMPRESSION_NONE = "none";

    private final AgentSnapshotProperties properties;
    private final RedisTemplate<String, byte[]> snapshotPartRedisTemplate;
    private final ObjectMapper objectMapper;

    public SnapshotPartService(AgentSnapshotProperties properties,
                               @Qualifier("snapshotPartRedisTemplate") RedisTemplate<String, byte[]> snapshotPartRedisTemplate,
                               ObjectMapper objectMapper) {
        this.properties = properties;
        this.snapshotPartRedisTemplate = snapshotPartRedisTemplate;
        this.objectMapper = objectMapper;
    }

    public int resolvePartSize(int requestedPartSize) {
        int fallback = properties.getDefaultPartSize() > 0 ? properties.getDefaultPartSize() : 512 * 1024;
        int effective = requestedPartSize > 0 ? requestedPartSize : fallback;
        int min = properties.getMinPartSize() > 0 ? properties.getMinPartSize() : 64 * 1024;
        int max = properties.getMaxPartSize() > 0 ? properties.getMaxPartSize() : 2 * 1024 * 1024;
        return Math.max(min, Math.min(max, effective));
    }

    public SnapshotPartsMeta getOrBuildMeta(String runId, String snapshotJson, int requestedPartSize) {
        validateRunId(runId);
        int partSize = resolvePartSize(requestedPartSize);
        SnapshotPartsMeta cached = loadMetaFromCache(runId, partSize);
        if (cached != null) {
            return cached;
        }
        PreparedSnapshot prepared = prepareSnapshot(runId, snapshotJson, partSize);
        cacheParts(runId, partSize, prepared);
        return prepared.meta();
    }

    public byte[] getPartBytes(String runId, String snapshotJson, int partIndex, int requestedPartSize) {
        validateRunId(runId);
        if (partIndex < 0) {
            throw new IllegalArgumentException("part_index must be >= 0");
        }
        SnapshotPartsMeta meta = getOrBuildMeta(runId, snapshotJson, requestedPartSize);
        if (meta.getTotalParts() == 0) {
            throw new IllegalArgumentException("snapshot has no parts");
        }
        if (partIndex >= meta.getTotalParts()) {
            throw new IllegalArgumentException("part_index out of range");
        }
        int partSize = meta.getPartSize();
        byte[] cached = snapshotPartRedisTemplate.opsForValue().get(partKey(runId, partSize, partIndex));
        if (cached != null) {
            return cached;
        }
        PreparedSnapshot prepared = prepareSnapshot(runId, snapshotJson, partSize);
        cacheParts(runId, partSize, prepared);
        if (partIndex >= prepared.parts().size()) {
            throw new IllegalArgumentException("part_index out of range");
        }
        return prepared.parts().get(partIndex);
    }

    private PreparedSnapshot prepareSnapshot(String runId, String snapshotJson, int partSize) {
        byte[] raw = nvl(snapshotJson).getBytes(StandardCharsets.UTF_8);
        byte[] payload;
        String compression;
        if (properties.isGzipEnabled()) {
            payload = gzip(raw);
            compression = COMPRESSION_GZIP;
        } else {
            payload = raw;
            compression = COMPRESSION_NONE;
        }
        String checksum = md5Hex(payload);
        List<byte[]> parts = split(payload, partSize);
        SnapshotPartsMeta meta = SnapshotPartsMeta.builder()
                .runId(runId)
                .partSize(partSize)
                .totalParts(parts.size())
                .uncompressedSize(raw.length)
                .compressedSize(payload.length)
                .compression(compression)
                .checksum(checksum)
                .build();
        return new PreparedSnapshot(meta, parts);
    }

    private void cacheParts(String runId, int partSize, PreparedSnapshot prepared) {
        long ttlSeconds = properties.getCacheTtlSeconds();
        Duration ttl = ttlSeconds > 0 ? Duration.ofSeconds(ttlSeconds) : null;
        for (int i = 0; i < prepared.parts().size(); i++) {
            String key = partKey(runId, partSize, i);
            snapshotPartRedisTemplate.opsForValue().set(key, prepared.parts().get(i));
            if (ttl != null) {
                snapshotPartRedisTemplate.expire(key, ttl);
            }
        }
        try {
            byte[] metaBytes = objectMapper.writeValueAsBytes(prepared.meta());
            String metaKey = metaKey(runId, partSize);
            snapshotPartRedisTemplate.opsForValue().set(metaKey, metaBytes);
            if (ttl != null) {
                snapshotPartRedisTemplate.expire(metaKey, ttl);
            }
        } catch (Exception e) {
            log.warn("Failed to cache snapshot parts meta for runId={}", runId, e);
        }
    }

    private SnapshotPartsMeta loadMetaFromCache(String runId, int partSize) {
        byte[] metaBytes = snapshotPartRedisTemplate.opsForValue().get(metaKey(runId, partSize));
        if (metaBytes == null || metaBytes.length == 0) {
            return null;
        }
        try {
            return objectMapper.readValue(metaBytes, SnapshotPartsMeta.class);
        } catch (Exception e) {
            log.debug("Failed to read cached snapshot meta for runId={}", runId, e);
            return null;
        }
    }

    private static List<byte[]> split(byte[] payload, int partSize) {
        if (payload.length == 0) {
            return List.of();
        }
        List<byte[]> parts = new ArrayList<>();
        for (int offset = 0; offset < payload.length; offset += partSize) {
            int length = Math.min(partSize, payload.length - offset);
            byte[] chunk = new byte[length];
            System.arraycopy(payload, offset, chunk, 0, length);
            parts.add(chunk);
        }
        return parts;
    }

    private static byte[] gzip(byte[] raw) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(raw.length);
             GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
            gzip.write(raw);
            gzip.finish();
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("failed to gzip snapshot", e);
        }
    }

    private static String md5Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("failed to compute snapshot checksum", e);
        }
    }

    private static void validateRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("run_id is required");
        }
    }

    private static String nvl(String text) {
        return text == null ? "" : text;
    }

    private static String partKey(String runId, int partSize, int index) {
        return KEY_PREFIX + runId + PARTS_SUFFIX + partSize + ":" + index;
    }

    private static String metaKey(String runId, int partSize) {
        return KEY_PREFIX + runId + PARTS_SUFFIX + partSize + META_SUFFIX;
    }

    private record PreparedSnapshot(SnapshotPartsMeta meta, List<byte[]> parts) {
    }
}
