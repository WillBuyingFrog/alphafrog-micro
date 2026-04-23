package world.willfrog.alphafrogmicro.common.service.config;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import world.willfrog.alphafrogmicro.common.dao.config.ConfigActiveDao;
import world.willfrog.alphafrogmicro.common.dao.config.ConfigSnapshotDao;
import world.willfrog.alphafrogmicro.common.dao.config.ConfigTypeDao;
import world.willfrog.alphafrogmicro.common.pojo.config.ConfigActive;
import world.willfrog.alphafrogmicro.common.pojo.config.ConfigSnapshot;
import world.willfrog.alphafrogmicro.common.pojo.config.ConfigType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 配置版本管理核心服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigProfileService {

    private final ConfigTypeDao configTypeDao;
    private final ConfigSnapshotDao configSnapshotDao;
    private final ConfigActiveDao configActiveDao;
    private final ObjectMapper objectMapper;
    private final ConfigService nacosConfigService;
    private final StringRedisTemplate redisTemplate;

    private static final String DEFAULT_GROUP = "alphafrog-config";

    /**
     * 基于已有版本派生新版本。
     */
    @Transactional
    public ConfigSnapshot derive(String typeName, String baseVersion, String patchType,
                                  JsonNode patch, String comment, String operatorId, boolean force) throws Exception {
        ConfigType type = configTypeDao.getByName(typeName);
        if (type == null) {
            throw new IllegalArgumentException("配置类型不存在: " + typeName);
        }

        ConfigSnapshot baseSnapshot = configSnapshotDao.getByTypeAndVersion(type.getId(), baseVersion);
        if (baseSnapshot == null) {
            throw new IllegalArgumentException("基础版本不存在: " + baseVersion);
        }

        // 校验 baseVersion 是否为最新（除非 force=true）
        if (!force) {
            List<ConfigSnapshot> allSnapshots = configSnapshotDao.listByType(type.getId());
            if (!allSnapshots.isEmpty() && !baseVersion.equals(allSnapshots.get(0).getVersion())) {
                throw new IllegalStateException("baseVersion 不是最新版本，请传 force=true 强制派生");
            }
        }

        // apply patch
        JsonNode baseNode = objectMapper.readTree(baseSnapshot.getContentJson());
        JsonNode resultNode = applyPatch(baseNode, patchType, patch);

        // schema 校验
        validateSchema(type, resultNode);

        // 生成新版本号（取最大版本号 + 1，避免删除后重复）
        int maxNum = configSnapshotDao.maxVersionNumberByType(type.getId());
        String newVersion = "v" + (maxNum + 1);

        String contentJson = objectMapper.writeValueAsString(resultNode);
        String md5 = md5Hex(contentJson);

        ConfigSnapshot snapshot = new ConfigSnapshot();
        snapshot.setTypeId(type.getId());
        snapshot.setVersion(newVersion);
        snapshot.setContentJson(contentJson);
        snapshot.setContentMd5(md5);
        snapshot.setComment(comment);
        snapshot.setCreatedBy(operatorId);
        snapshot.setCreatedAt(OffsetDateTime.now());

        configSnapshotDao.insert(snapshot);

        // 重新读取以获取 id
        ConfigSnapshot saved = configSnapshotDao.getByTypeAndVersion(type.getId(), newVersion);
        log.info("[ConfigProfileService] 派生配置成功 type={} base={} new={} operator={}",
                typeName, baseVersion, newVersion, operatorId);
        return saved;
    }

    /**
     * 从头创建新版本（完整 JSON 替换）。
     */
    @Transactional
    public ConfigSnapshot createFromScratch(String typeName, JsonNode fullConfig,
                                             String comment, String operatorId) throws Exception {
        ConfigType type = configTypeDao.getByName(typeName);
        if (type == null) {
            throw new IllegalArgumentException("配置类型不存在: " + typeName);
        }

        validateSchema(type, fullConfig);

        int maxNum = configSnapshotDao.maxVersionNumberByType(type.getId());
        String newVersion = "v" + (maxNum + 1);

        String contentJson = objectMapper.writeValueAsString(fullConfig);
        String md5 = md5Hex(contentJson);

        ConfigSnapshot snapshot = new ConfigSnapshot();
        snapshot.setTypeId(type.getId());
        snapshot.setVersion(newVersion);
        snapshot.setContentJson(contentJson);
        snapshot.setContentMd5(md5);
        snapshot.setComment(comment);
        snapshot.setCreatedBy(operatorId);
        snapshot.setCreatedAt(OffsetDateTime.now());

        configSnapshotDao.insert(snapshot);

        ConfigSnapshot saved = configSnapshotDao.getByTypeAndVersion(type.getId(), newVersion);
        log.info("[ConfigProfileService] 创建配置成功 type={} version={} operator={}",
                typeName, newVersion, operatorId);
        return saved;
    }

    /**
     * 激活指定版本（切换/回滚）。
     */
    @Transactional
    public void activate(String typeName, String version, Integer expectedSnapshotId,
                         String operatorId) throws Exception {
        ConfigType type = configTypeDao.getByName(typeName);
        if (type == null) {
            throw new IllegalArgumentException("配置类型不存在: " + typeName);
        }

        ConfigSnapshot target = configSnapshotDao.getByTypeAndVersion(type.getId(), version);
        if (target == null) {
            throw new IllegalArgumentException("目标版本不存在: " + version);
        }

        // 乐观锁校验
        ConfigActive currentActive = configActiveDao.getByType(type.getId());
        if (currentActive != null && !currentActive.getSnapshotId().equals(expectedSnapshotId)) {
            throw new IllegalStateException("配置已被他人修改，expectedSnapshotId 不匹配，请刷新后重试");
        }

        // publish 到 Nacos
        try {
            boolean success = nacosConfigService.publishConfig(
                    type.getDataId(), type.getConfigGroup(), target.getContentJson());
            if (!success) {
                throw new RuntimeException("Nacos publishConfig 返回 false");
            }
        } catch (NacosException e) {
            throw new RuntimeException("Nacos 发布配置失败", e);
        }

        // 更新激活版本
        ConfigActive active = new ConfigActive();
        active.setTypeId(type.getId());
        active.setSnapshotId(target.getId());
        active.setActivatedAt(OffsetDateTime.now());
        active.setActivatedBy(operatorId);
        configActiveDao.upsert(active);

        log.info("[ConfigProfileService] 激活配置成功 type={} version={} snapshotId={} operator={}",
                typeName, version, target.getId(), operatorId);
    }

    /**
     * 获取当前激活配置 + 各副本生效状态。
     */
    public Map<String, Object> getActiveWithReplicas(String typeName) {
        ConfigType type = configTypeDao.getByName(typeName);
        if (type == null) {
            throw new IllegalArgumentException("配置类型不存在: " + typeName);
        }

        ConfigActive active = configActiveDao.getByType(type.getId());
        ConfigSnapshot snapshot = null;
        if (active != null) {
            snapshot = configSnapshotDao.getById(active.getSnapshotId());
        }

        // 从 Redis 聚合各副本状态
        List<Map<String, Object>> replicas = new ArrayList<>();
        if (redisTemplate != null) {
            try {
                String pattern = String.format("config:state:*:%s", type.getDataId());
                var keys = redisTemplate.keys(pattern);
                if (keys != null) {
                    for (String key : keys) {
                        String value = redisTemplate.opsForValue().get(key);
                        if (value != null) {
                            JsonNode node = objectMapper.readTree(value);
                            Map<String, Object> replica = new LinkedHashMap<>();
                            String[] parts = key.split(":");
                            replica.put("serviceName", parts.length > 2 ? parts[2] : "");
                            replica.put("md5", node.path("md5").asText());
                            replica.put("loadedAt", node.path("loadedAt").asText());
                            replicas.add(replica);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[ConfigProfileService] Redis 状态聚合失败", e);
            }
        }

        boolean synced = snapshot != null && replicas.stream()
                .allMatch(r -> snapshot.getContentMd5().equals(r.get("md5")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        result.put("activeSnapshot", snapshot);
        result.put("replicas", replicas);
        result.put("synced", synced);
        return result;
    }

    public List<ConfigSnapshot> listSnapshots(String typeName) {
        ConfigType type = configTypeDao.getByName(typeName);
        if (type == null) {
            throw new IllegalArgumentException("配置类型不存在: " + typeName);
        }
        return configSnapshotDao.listByType(type.getId());
    }

    public ConfigSnapshot getSnapshot(String typeName, String version) {
        ConfigType type = configTypeDao.getByName(typeName);
        if (type == null) {
            throw new IllegalArgumentException("配置类型不存在: " + typeName);
        }
        return configSnapshotDao.getByTypeAndVersion(type.getId(), version);
    }

    // ========== 内部方法 ==========

    private JsonNode applyPatch(JsonNode baseNode, String patchType, JsonNode patch) {
        if ("ops".equals(patchType)) {
            return applyOpsPatch(baseNode, patch);
        } else if ("merge".equals(patchType)) {
            return applyMergePatch(baseNode, patch);
        } else {
            throw new IllegalArgumentException("不支持的 patchType: " + patchType);
        }
    }

    private JsonNode applyOpsPatch(JsonNode baseNode, JsonNode patch) {
        JsonNode result = baseNode.deepCopy();
        if (!patch.isArray()) {
            throw new IllegalArgumentException("ops patch 必须是数组");
        }
        for (JsonNode opNode : patch) {
            String op = opNode.get("op").asText();
            String path = opNode.get("path").asText();
            JsonNode value = opNode.has("value") ? opNode.get("value") : null;

            String[] segments = path.split("/");
            // 去掉开头的空字符串（因为 path 以 / 开头）
            List<String> keys = Arrays.stream(segments)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());

            switch (op) {
                case "set" -> setAtPath(result, keys, value);
                case "add" -> addAtPath(result, keys, value);
                case "remove" -> removeAtPath(result, keys, value);
                default -> throw new IllegalArgumentException("不支持的 op: " + op);
            }
        }
        return result;
    }

    private JsonNode applyMergePatch(JsonNode baseNode, JsonNode patch) {
        // 简单实现：递归合并对象，数组整体替换
        return mergeRecursive(baseNode, patch);
    }

    private JsonNode mergeRecursive(JsonNode base, JsonNode patch) {
        if (!patch.isObject()) {
            return patch.deepCopy();
        }
        ObjectNode result = ((ObjectNode) base).deepCopy();
        patch.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (value.isNull()) {
                result.remove(key);
            } else if (value.isObject() && result.has(key) && result.get(key).isObject()) {
                result.set(key, mergeRecursive(result.get(key), value));
            } else {
                result.set(key, value.deepCopy());
            }
        });
        return result;
    }

    private void setAtPath(JsonNode root, List<String> keys, JsonNode value) {
        JsonNode parent = findParent(root, keys);
        String lastKey = keys.get(keys.size() - 1);
        if (parent.isObject()) {
            ((ObjectNode) parent).set(lastKey, value.deepCopy());
        } else {
            throw new IllegalArgumentException("set 目标必须是对象: " + String.join("/", keys));
        }
    }

    private void addAtPath(JsonNode root, List<String> keys, JsonNode value) {
        JsonNode parent = findParent(root, keys);
        String lastKey = keys.get(keys.size() - 1);
        if (parent.isObject()) {
            JsonNode target = parent.get(lastKey);
            if (target != null && target.isArray()) {
                ((ArrayNode) target).add(value.deepCopy());
            } else {
                ((ObjectNode) parent).set(lastKey, value.deepCopy());
            }
        } else if (parent.isArray()) {
            ((ArrayNode) parent).add(value.deepCopy());
        } else {
            throw new IllegalArgumentException("add 目标必须是对象或数组");
        }
    }

    private void removeAtPath(JsonNode root, List<String> keys, JsonNode value) {
        JsonNode parent = findParent(root, keys);
        String lastKey = keys.get(keys.size() - 1);
        if (parent.isObject()) {
            JsonNode target = parent.get(lastKey);
            if (target != null && target.isArray() && value != null) {
                // 按值从数组中删除
                ArrayNode array = (ArrayNode) target;
                ArrayNode newArray = objectMapper.createArrayNode();
                String valueToRemove = value.asText();
                for (JsonNode item : array) {
                    if (!item.asText().equals(valueToRemove)) {
                        newArray.add(item);
                    }
                }
                ((ObjectNode) parent).set(lastKey, newArray);
            } else {
                ((ObjectNode) parent).remove(lastKey);
            }
        } else {
            throw new IllegalArgumentException("remove 目标必须是对象");
        }
    }

    private JsonNode findParent(JsonNode root, List<String> keys) {
        JsonNode current = root;
        for (int i = 0; i < keys.size() - 1; i++) {
            current = current.get(keys.get(i));
            if (current == null) {
                throw new IllegalArgumentException("路径不存在: " + keys.get(i));
            }
        }
        return current;
    }

    private void validateSchema(ConfigType type, JsonNode configNode) {
        if (type.getSchemaJson() == null || type.getSchemaJson().isBlank()) {
            return;
        }
        try {
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
            JsonSchema schema = factory.getSchema(type.getSchemaJson());
            Set<ValidationMessage> errors = schema.validate(configNode);
            if (!errors.isEmpty()) {
                String errorMsg = errors.stream()
                        .map(ValidationMessage::getMessage)
                        .collect(Collectors.joining("; "));
                throw new IllegalArgumentException("Schema 校验失败: " + errorMsg);
            }
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException) {
                throw e;
            }
            throw new RuntimeException("Schema 校验异常", e);
        }
    }

    private String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("MD5 计算失败", e);
        }
    }
}
