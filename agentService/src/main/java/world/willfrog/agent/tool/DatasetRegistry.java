package world.willfrog.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
@Slf4j
public class DatasetRegistry {

    private static final String META_PREFIX = "dataset:meta:";
    private static final String INDEX_PREFIX = "dataset:index:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${agent.tools.market-data.dataset.path:/data/agent_datasets}")
    private String datasetPath;

    @Value("${agent.tools.market-data.dataset.enabled:true}")
    private boolean enabled;

    @Value("${agent.tools.market-data.dataset.cache-ttl-seconds:604800}")
    private long ttlSeconds;

    @Value("${agent.tools.market-data.dataset.allow-range-reuse:true}")
    private boolean allowRangeReuse;

    @Value("${agent.tools.market-data.dataset.cleanup-scan-count:500}")
    private int scanCount;

    public DatasetRegistry(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Optional<DatasetMeta> findReusable(String type, String tsCode, String startDate, String endDate, List<String> columns) {
        if (!enabled) {
            return Optional.empty();
        }
        String queryKey = buildQueryKey(type, tsCode, startDate, endDate, columns);
        Optional<DatasetMeta> exact = loadMeta(queryKey);
        if (exact.isPresent()) {
            DatasetMeta meta = exact.get();
            if (isExpired(meta) || !datasetFilesExist(meta)) {
                cleanupMeta(meta);
            } else {
                touchMeta(meta);
                return Optional.of(meta);
            }
        }

        if (!allowRangeReuse) {
            return Optional.empty();
        }

        String indexKey = indexKey(type, tsCode);
        Set<String> queryKeys = redisTemplate.opsForSet().members(indexKey);
        if (queryKeys == null || queryKeys.isEmpty()) {
            return Optional.empty();
        }

        Long targetStart = parseDateToLong(startDate);
        Long targetEnd = parseDateToLong(endDate);
        if (targetStart == null || targetEnd == null) {
            return Optional.empty();
        }

        String columnSignature = String.join(",", columns);
        List<DatasetMeta> candidates = new ArrayList<>();
        for (String candidateKey : queryKeys) {
            if (candidateKey.equals(queryKey)) {
                continue;
            }
            Optional<DatasetMeta> candidate = loadMeta(candidateKey);
            if (candidate.isEmpty()) {
                continue;
            }
            DatasetMeta meta = candidate.get();
            if (!columnSignature.equals(meta.getColumnsSignature())) {
                continue;
            }
            Long metaStart = parseDateToLong(meta.getStartDate());
            Long metaEnd = parseDateToLong(meta.getEndDate());
            if (metaStart == null || metaEnd == null) {
                continue;
            }
            if (metaStart <= targetStart && metaEnd >= targetEnd && !isExpired(meta) && datasetFilesExist(meta)) {
                candidates.add(meta);
            }
        }

        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        candidates.sort(Comparator.comparingLong(meta -> rangeLength(meta)));
        DatasetMeta selected = candidates.get(0);
        touchMeta(selected);
        return Optional.of(selected);
    }

    public void registerDataset(String type, String tsCode, String startDate, String endDate,
                                List<String> columns, String datasetId, int rowCount) {
        if (!enabled || datasetId == null || datasetId.isEmpty()) {
            return;
        }
        String queryKey = buildQueryKey(type, tsCode, startDate, endDate, columns);
        long now = Instant.now().toEpochMilli();
        long expireAt = ttlSeconds > 0 ? now + ttlSeconds * 1000L : Long.MAX_VALUE;
        String datasetDir = Paths.get(datasetPath, datasetId).toAbsolutePath().toString();

        DatasetMeta meta = DatasetMeta.builder()
                .datasetId(datasetId)
                .queryKey(queryKey)
                .type(type)
                .tsCode(tsCode)
                .startDate(startDate)
                .endDate(endDate)
                .columns(columns)
                .columnsSignature(String.join(",", columns))
                .rowCount(rowCount)
                .path(datasetDir)
                .createdAt(now)
                .lastAccessAt(now)
                .hitCount(1)
                .ttlSeconds(ttlSeconds)
                .expireAt(expireAt)
                .build();

        String metaKey = metaKey(queryKey);
        try {
            redisTemplate.opsForValue().set(metaKey, objectMapper.writeValueAsString(meta));
            redisTemplate.opsForSet().add(indexKey(type, tsCode), queryKey);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize dataset meta: {}", datasetId, e);
        }
    }

    @Scheduled(fixedDelayString = "${agent.tools.market-data.dataset.cleanup-interval-ms:600000}")
    public void cleanupExpiredDatasets() {
        if (!enabled) {
            return;
        }
        long now = Instant.now().toEpochMilli();
        Cursor<byte[]> cursor = null;
        try {
            cursor = redisTemplate.getConnectionFactory()
                    .getConnection()
                    .scan(ScanOptions.scanOptions().match(META_PREFIX + "*").count(scanCount).build());
            while (cursor.hasNext()) {
                String key = new String(cursor.next(), StandardCharsets.UTF_8);
                String json = redisTemplate.opsForValue().get(key);
                if (json == null || json.isEmpty()) {
                    continue;
                }
                DatasetMeta meta;
                try {
                    meta = objectMapper.readValue(json, DatasetMeta.class);
                } catch (JsonProcessingException e) {
                    log.warn("Skip invalid dataset meta, key={}", key, e);
                    continue;
                }
                if (now >= meta.getExpireAt()) {
                    deleteDatasetFiles(meta);
                    redisTemplate.delete(key);
                    redisTemplate.opsForSet().remove(indexKey(meta.getType(), meta.getTsCode()), meta.getQueryKey());
                }
            }
        } catch (Exception e) {
            log.warn("Dataset cleanup scan failed", e);
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Exception e) {
                    log.debug("Failed to close redis scan cursor", e);
                }
            }
        }
    }

    private Optional<DatasetMeta> loadMeta(String queryKey) {
        String json = redisTemplate.opsForValue().get(metaKey(queryKey));
        if (json == null || json.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, DatasetMeta.class));
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse dataset meta for key {}", queryKey, e);
            return Optional.empty();
        }
    }

    private void touchMeta(DatasetMeta meta) {
        long now = Instant.now().toEpochMilli();
        meta.setLastAccessAt(now);
        meta.setHitCount(meta.getHitCount() + 1);
        if (ttlSeconds > 0) {
            meta.setExpireAt(now + ttlSeconds * 1000L);
            meta.setTtlSeconds(ttlSeconds);
        }
        try {
            redisTemplate.opsForValue().set(metaKey(meta.getQueryKey()), objectMapper.writeValueAsString(meta));
        } catch (JsonProcessingException e) {
            log.warn("Failed to update dataset meta for key {}", meta.getQueryKey(), e);
        }
    }

    private boolean isExpired(DatasetMeta meta) {
        return ttlSeconds > 0 && Instant.now().toEpochMilli() >= meta.getExpireAt();
    }

    private boolean datasetFilesExist(DatasetMeta meta) {
        File dir = new File(meta.getPath());
        if (!dir.exists() || !dir.isDirectory()) {
            return false;
        }
        File csv = new File(dir, meta.getDatasetId() + ".csv");
        return csv.exists();
    }

    private void cleanupMeta(DatasetMeta meta) {
        try {
            redisTemplate.delete(metaKey(meta.getQueryKey()));
            redisTemplate.opsForSet().remove(indexKey(meta.getType(), meta.getTsCode()), meta.getQueryKey());
        } catch (Exception e) {
            log.warn("Failed to cleanup meta for {}", meta.getDatasetId(), e);
        }
    }

    private void deleteDatasetFiles(DatasetMeta meta) {
        Path dir = Paths.get(meta.getPath());
        if (!Files.exists(dir)) {
            return;
        }
        try {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            log.warn("Failed to delete dataset file {}", path, e);
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to walk dataset dir {}", dir, e);
        }
    }

    private String metaKey(String queryKey) {
        return META_PREFIX + queryKey;
    }

    private String indexKey(String type, String tsCode) {
        return INDEX_PREFIX + type + ":" + tsCode;
    }

    private String buildQueryKey(String type, String tsCode, String startDate, String endDate, List<String> columns) {
        String raw = type + "|" + tsCode + "|" + startDate + "|" + endDate + "|" + String.join(",", columns);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashed) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private Long parseDateToLong(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String raw = value.trim();
        if (raw.matches("\\d{13}")) {
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        Long converted = DateConvertUtils.convertDateStrToLong(raw, "yyyyMMdd");
        if (converted == null || converted <= 0) {
            return null;
        }
        return converted;
    }

    private long rangeLength(DatasetMeta meta) {
        Long start = parseDateToLong(meta.getStartDate());
        Long end = parseDateToLong(meta.getEndDate());
        if (start == null || end == null) {
            return Long.MAX_VALUE;
        }
        return Math.abs(end - start);
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DatasetMeta {
        private String datasetId;
        private String queryKey;
        private String type;
        private String tsCode;
        private String startDate;
        private String endDate;
        private List<String> columns;
        private String columnsSignature;
        private int rowCount;
        private String path;
        private long createdAt;
        private long lastAccessAt;
        private int hitCount;
        private long ttlSeconds;
        private long expireAt;
    }
}
