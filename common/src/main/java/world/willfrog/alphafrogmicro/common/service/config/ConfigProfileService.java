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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import world.willfrog.alphafrogmicro.common.config.ConfigJsonCanonicalizer;
import world.willfrog.alphafrogmicro.common.dao.config.ConfigAuditLogDao;
import world.willfrog.alphafrogmicro.common.dao.config.ConfigActiveDao;
import world.willfrog.alphafrogmicro.common.dao.config.ConfigSnapshotDao;
import world.willfrog.alphafrogmicro.common.dao.config.ConfigTypeDao;
import world.willfrog.alphafrogmicro.common.exception.config.ConfigConflictException;
import world.willfrog.alphafrogmicro.common.exception.config.ConfigNotFoundException;
import world.willfrog.alphafrogmicro.common.exception.config.ConfigPublishException;
import world.willfrog.alphafrogmicro.common.pojo.config.ConfigAuditLog;
import world.willfrog.alphafrogmicro.common.pojo.config.ConfigActive;
import world.willfrog.alphafrogmicro.common.pojo.config.ConfigSnapshot;
import world.willfrog.alphafrogmicro.common.pojo.config.ConfigType;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 配置版本管理核心服务。
 */
@Slf4j
@Service
public class ConfigProfileService {

    private final ConfigTypeDao configTypeDao;
    private final ConfigSnapshotDao configSnapshotDao;
    private final ConfigActiveDao configActiveDao;
    private final ConfigAuditLogDao configAuditLogDao;
    private final ObjectMapper objectMapper;
    private final ConfigService nacosConfigService;
    private final StringRedisTemplate redisTemplate;

    @Autowired
    public ConfigProfileService(ConfigTypeDao configTypeDao,
                                ConfigSnapshotDao configSnapshotDao,
                                ConfigActiveDao configActiveDao,
                                ConfigAuditLogDao configAuditLogDao,
                                ObjectMapper objectMapper,
                                ObjectProvider<ConfigService> nacosConfigServiceProvider,
                                StringRedisTemplate redisTemplate) {
        this(configTypeDao,
                configSnapshotDao,
                configActiveDao,
                configAuditLogDao,
                objectMapper,
                nacosConfigServiceProvider.getIfAvailable(),
                redisTemplate);
    }

    public ConfigProfileService(ConfigTypeDao configTypeDao,
                                ConfigSnapshotDao configSnapshotDao,
                                ConfigActiveDao configActiveDao,
                                ConfigAuditLogDao configAuditLogDao,
                                ObjectMapper objectMapper,
                                ConfigService nacosConfigService,
                                StringRedisTemplate redisTemplate) {
        this.configTypeDao = configTypeDao;
        this.configSnapshotDao = configSnapshotDao;
        this.configActiveDao = configActiveDao;
        this.configAuditLogDao = configAuditLogDao;
        this.objectMapper = objectMapper;
        this.nacosConfigService = nacosConfigService;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 基于已有版本派生新版本。
     */
    @Transactional
    public ConfigSnapshot derive(String typeName, String baseVersion, String patchType,
                                  JsonNode patch, String comment, String operatorId, boolean force) throws Exception {
        DerivedConfigContent derivedContent = buildDerivedContent(typeName, baseVersion, patchType, patch, force);
        ConfigType type = derivedContent.type();

        // 生成新版本号（取最大版本号 + 1，避免删除后重复）
        int maxNum = configSnapshotDao.maxVersionNumberByType(type.getId());
        String newVersion = "v" + (maxNum + 1);

        ConfigSnapshot snapshot = new ConfigSnapshot();
        snapshot.setTypeId(type.getId());
        snapshot.setVersion(newVersion);
        snapshot.setContentJson(derivedContent.contentJson());
        snapshot.setContentMd5(derivedContent.contentMd5());
        snapshot.setComment(comment);
        snapshot.setCreatedBy(operatorId);
        snapshot.setCreatedAt(OffsetDateTime.now());

        configSnapshotDao.insert(snapshot);

        // 重新读取以获取 id
        ConfigSnapshot saved = configSnapshotDao.getByTypeAndVersion(type.getId(), newVersion);
        insertAuditLog(type.getId(), "DERIVE", saved == null ? null : saved.getId(), baseVersion,
                operatorId, comment);
        log.info("[ConfigProfileService] 派生配置成功 type={} base={} new={} operator={}",
                typeName, baseVersion, newVersion, operatorId);
        return saved;
    }

    /**
     * 预览基于已有版本派生后的配置内容，不写库、不发布 Nacos。
     */
    public Map<String, Object> previewDerive(String typeName, String baseVersion, String patchType,
                                             JsonNode patch, boolean force) throws Exception {
        DerivedConfigContent derivedContent = buildDerivedContent(typeName, baseVersion, patchType, patch, force);

        Map<String, Object> base = new LinkedHashMap<>();
        ConfigSnapshot baseSnapshot = derivedContent.baseSnapshot();
        base.put("id", baseSnapshot.getId());
        base.put("version", baseSnapshot.getVersion());
        base.put("contentMd5", ConfigJsonCanonicalizer.md5Hex(baseSnapshot.getContentJson()));

        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("typeId", derivedContent.type().getId());
        preview.put("baseVersion", baseSnapshot.getVersion());
        preview.put("patchType", patchType);
        preview.put("contentJson", derivedContent.contentJson());
        preview.put("contentMd5", derivedContent.contentMd5());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("base", base);
        result.put("preview", preview);
        return result;
    }

    /**
     * 从头创建新版本（完整 JSON 替换）。
     */
    @Transactional
    public ConfigSnapshot createFromScratch(String typeName, JsonNode fullConfig,
                                             String comment, String operatorId) throws Exception {
        ConfigType type = getTypeOrThrow(typeName);

        validateSchema(type, fullConfig);

        int maxNum = configSnapshotDao.maxVersionNumberByType(type.getId());
        String newVersion = "v" + (maxNum + 1);

        String contentJson = canonicalJson(fullConfig);
        String md5 = ConfigJsonCanonicalizer.md5Hex(contentJson);

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
        insertAuditLog(type.getId(), "CREATE", saved == null ? null : saved.getId(), null,
                operatorId, comment);
        log.info("[ConfigProfileService] 创建配置成功 type={} version={} operator={}",
                typeName, newVersion, operatorId);
        return saved;
    }

    /**
     * 激活指定版本（切换/回滚）。
     */
    @Transactional
    public void activate(String typeName, String version, Integer expectedSnapshotId,
                         String operatorId) {
        ConfigType type = getTypeOrThrow(typeName);
        ConfigSnapshot target = getSnapshotOrThrow(type, version, "目标版本不存在: " + version);

        ConfigActive active = new ConfigActive();
        active.setTypeId(type.getId());
        active.setSnapshotId(target.getId());
        active.setActivatedAt(OffsetDateTime.now());
        active.setActivatedBy(operatorId);

        int updatedRows = expectedSnapshotId == null
                ? configActiveDao.insertIfAbsent(active)
                : configActiveDao.updateIfSnapshotMatches(
                        type.getId(),
                        target.getId(),
                        expectedSnapshotId,
                        active.getActivatedAt(),
                        operatorId);
        if (updatedRows != 1) {
            throw new ConfigConflictException("配置已被他人修改，expectedSnapshotId 不匹配，请刷新后重试");
        }

        registerAfterCommitPublish(type, target, operatorId);

        log.info("[ConfigProfileService] 激活配置成功 type={} version={} snapshotId={} operator={}",
                typeName, version, target.getId(), operatorId);
    }

    /**
     * 获取当前激活配置 + 各副本生效状态。
     */
    public Map<String, Object> getActiveWithReplicas(String typeName) {
        ConfigType type = getTypeOrThrow(typeName);

        ConfigActive active = configActiveDao.getByType(type.getId());
        ConfigSnapshot snapshot = null;
        if (active != null) {
            snapshot = configSnapshotDao.getById(active.getSnapshotId());
        }

        // 从 Redis 聚合各副本状态
        List<Map<String, Object>> replicas = new ArrayList<>();
        if (redisTemplate != null) {
            try {
                String pattern = String.format("config:state:*:*:%s", type.getDataId());
                var keys = redisTemplate.keys(pattern);
                if (keys != null) {
                    for (String key : keys) {
                        String value = redisTemplate.opsForValue().get(key);
                        if (value != null) {
                            JsonNode node = objectMapper.readTree(value);
                            Map<String, Object> replica = new LinkedHashMap<>();
                            String[] parts = key.split(":");
                            replica.put("serviceName", parts.length > 2 ? parts[2] : "");
                            replica.put("instanceId", parts.length > 3 ? parts[3] : "");
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

        String activeSnapshotMd5 = snapshot == null ? null : ConfigJsonCanonicalizer.md5Hex(snapshot.getContentJson());
        if (snapshot != null && activeSnapshotMd5 != null) {
            snapshot.setContentMd5(activeSnapshotMd5);
        }
        boolean synced = activeSnapshotMd5 != null && !replicas.isEmpty() && replicas.stream()
                .allMatch(r -> activeSnapshotMd5.equals(r.get("md5")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        result.put("activeSnapshot", snapshot);
        result.put("activeContentMd5", activeSnapshotMd5);
        result.put("replicas", replicas);
        result.put("replicaCount", replicas.size());
        result.put("synced", synced);
        return result;
    }

    public List<ConfigSnapshot> listSnapshots(String typeName) {
        ConfigType type = getTypeOrThrow(typeName);
        return configSnapshotDao.listByType(type.getId());
    }

    public List<ConfigType> listTypes() {
        return configTypeDao.listAll();
    }

    public ConfigSnapshot getSnapshot(String typeName, String version) {
        ConfigType type = getTypeOrThrow(typeName);
        return configSnapshotDao.getByTypeAndVersion(type.getId(), version);
    }

    public List<ConfigAuditLog> listAuditLogs(String typeName, int limit) {
        ConfigType type = getTypeOrThrow(typeName);
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return configAuditLogDao.listByType(type.getId(), safeLimit);
    }

    @Transactional
    public void deleteSnapshot(String typeName, String version, String operatorId) {
        ConfigType type = getTypeOrThrow(typeName);
        ConfigSnapshot snapshot = getSnapshotOrThrow(type, version, "目标版本不存在: " + version);
        ConfigActive active = configActiveDao.getByType(type.getId());
        if (active != null && Objects.equals(active.getSnapshotId(), snapshot.getId())) {
            throw new ConfigConflictException("当前激活版本不能删除，请先切换到其它版本");
        }
        int deleted = configSnapshotDao.deleteByTypeAndVersion(type.getId(), version);
        if (deleted != 1) {
            throw new ConfigNotFoundException("目标版本不存在: " + version);
        }
        insertAuditLog(type.getId(), "DELETE", snapshot.getId(), version, operatorId, null);
    }

    // ========== 内部方法 ==========

    private DerivedConfigContent buildDerivedContent(String typeName, String baseVersion, String patchType,
                                                     JsonNode patch, boolean force) throws Exception {
        ConfigType type = getTypeOrThrow(typeName);
        ConfigSnapshot baseSnapshot = getSnapshotOrThrow(type, baseVersion, "基础版本不存在: " + baseVersion);

        // 校验 baseVersion 是否为最新（除非 force=true）
        if (!force) {
            List<ConfigSnapshot> allSnapshots = configSnapshotDao.listByType(type.getId());
            if (!allSnapshots.isEmpty() && !baseVersion.equals(allSnapshots.get(0).getVersion())) {
                throw new ConfigConflictException("baseVersion 不是最新版本，请传 force=true 强制派生");
            }
        }

        JsonNode baseNode = objectMapper.readTree(baseSnapshot.getContentJson());
        JsonNode resultNode = applyPatch(baseNode, patchType, patch);
        validateSchema(type, resultNode);

        String contentJson = canonicalJson(resultNode);
        String md5 = ConfigJsonCanonicalizer.md5Hex(contentJson);
        return new DerivedConfigContent(type, baseSnapshot, contentJson, md5);
    }

    private record DerivedConfigContent(ConfigType type,
                                        ConfigSnapshot baseSnapshot,
                                        String contentJson,
                                        String contentMd5) {
    }

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

            List<String> keys = parseJsonPointer(path);

            switch (op) {
                case "set" -> setAtPath(result, keys, requirePatchValue(op, value));
                case "replace" -> setAtPath(result, keys, requirePatchValue(op, value));
                case "add" -> addAtPath(result, keys, requirePatchValue(op, value));
                case "remove" -> removeAtPath(result, keys, value);
                case "add_if_absent" -> addIfAbsentAtPath(result, keys, requirePatchValue(op, value));
                case "remove_value" -> removeValueAtPath(result, keys, value);
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

    private JsonNode requirePatchValue(String op, JsonNode value) {
        if (value == null) {
            throw new IllegalArgumentException(op + " 操作必须提供 value");
        }
        return value;
    }

    private List<String> parseJsonPointer(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("ops path 不能为空");
        }
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("ops path 必须以 / 开头: " + path);
        }
        return Arrays.stream(path.substring(1).split("/", -1))
                .map(this::unescapeJsonPointerSegment)
                .collect(Collectors.toList());
    }

    private String unescapeJsonPointerSegment(String segment) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segment.length(); i++) {
            char ch = segment.charAt(i);
            if (ch == '~') {
                if (i + 1 >= segment.length()) {
                    throw new IllegalArgumentException("非法 JSON Pointer 转义: " + segment);
                }
                char next = segment.charAt(++i);
                if (next == '0') {
                    sb.append('~');
                } else if (next == '1') {
                    sb.append('/');
                } else {
                    throw new IllegalArgumentException("非法 JSON Pointer 转义: ~" + next);
                }
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private void setAtPath(JsonNode root, List<String> keys, JsonNode value) {
        JsonNode parent = findParent(root, keys);
        String lastKey = keys.get(keys.size() - 1);
        if (parent.isObject()) {
            ((ObjectNode) parent).set(lastKey, value.deepCopy());
        } else if (parent.isArray()) {
            int index = parseArrayIndex(lastKey, ((ArrayNode) parent).size(), false);
            ((ArrayNode) parent).set(index, value.deepCopy());
        } else {
            throw new IllegalArgumentException("set 目标父节点必须是对象或数组: " + String.join("/", keys));
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
            ArrayNode array = (ArrayNode) parent;
            if ("-".equals(lastKey)) {
                array.add(value.deepCopy());
            } else {
                int index = parseArrayIndex(lastKey, array.size(), true);
                array.insert(index, value.deepCopy());
            }
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
                removeArrayValues((ArrayNode) target, value);
            } else {
                ((ObjectNode) parent).remove(lastKey);
            }
        } else if (parent.isArray()) {
            ArrayNode array = (ArrayNode) parent;
            int index = parseArrayIndex(lastKey, array.size(), false);
            array.remove(index);
        } else {
            throw new IllegalArgumentException("remove 目标父节点必须是对象或数组");
        }
    }

    private void addIfAbsentAtPath(JsonNode root, List<String> keys, JsonNode value) {
        JsonNode target = findNode(root, keys);
        if (!target.isArray()) {
            throw new IllegalArgumentException("add_if_absent 目标必须是数组: " + String.join("/", keys));
        }
        ArrayNode array = (ArrayNode) target;
        for (JsonNode item : array) {
            if (item.equals(value)) {
                return;
            }
        }
        array.add(value.deepCopy());
    }

    private void removeValueAtPath(JsonNode root, List<String> keys, JsonNode value) {
        if (value == null) {
            throw new IllegalArgumentException("remove_value 必须提供 value");
        }
        JsonNode target = findNode(root, keys);
        if (!target.isArray()) {
            throw new IllegalArgumentException("remove_value 目标必须是数组: " + String.join("/", keys));
        }
        removeArrayValues((ArrayNode) target, value);
    }

    private void removeArrayValues(ArrayNode array, JsonNode value) {
        for (int i = array.size() - 1; i >= 0; i--) {
            if (array.get(i).equals(value)) {
                array.remove(i);
            }
        }
    }

    private JsonNode findParent(JsonNode root, List<String> keys) {
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("ops path 必须指向对象字段或数组元素");
        }
        if (keys.size() == 1) {
            return root;
        }
        return findNode(root, keys.subList(0, keys.size() - 1));
    }

    private JsonNode findNode(JsonNode root, List<String> keys) {
        JsonNode current = root;
        for (String key : keys) {
            if (current == null) {
                throw new IllegalArgumentException("路径不存在: " + String.join("/", keys));
            }
            if (current.isObject()) {
                current = current.get(key);
            } else if (current.isArray()) {
                int index = parseArrayIndex(key, current.size(), false);
                current = current.get(index);
            } else {
                throw new IllegalArgumentException("路径中间节点必须是对象或数组: " + key);
            }
            if (current == null) {
                throw new IllegalArgumentException("路径不存在: " + key);
            }
        }
        return current;
    }

    private int parseArrayIndex(String raw, int size, boolean allowEnd) {
        if ("-".equals(raw)) {
            if (allowEnd) {
                return size;
            }
            throw new IllegalArgumentException("只有 add 操作支持数组末尾 '-'");
        }
        try {
            int index = Integer.parseInt(raw);
            int max = allowEnd ? size : size - 1;
            if (index < 0 || index > max) {
                throw new IllegalArgumentException("数组下标越界: " + raw + " size=" + size);
            }
            return index;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("数组下标必须是整数: " + raw, ex);
        }
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

    private String canonicalJson(JsonNode node) throws Exception {
        return ConfigJsonCanonicalizer.canonicalJson(node);
    }

    private ConfigType getTypeOrThrow(String typeName) {
        ConfigType type = configTypeDao.getByName(typeName);
        if (type == null) {
            throw new ConfigNotFoundException("配置类型不存在: " + typeName);
        }
        return type;
    }

    private ConfigSnapshot getSnapshotOrThrow(ConfigType type, String version, String message) {
        ConfigSnapshot snapshot = configSnapshotDao.getByTypeAndVersion(type.getId(), version);
        if (snapshot == null) {
            throw new ConfigNotFoundException(message);
        }
        return snapshot;
    }

    private void registerAfterCommitPublish(ConfigType type, ConfigSnapshot target, String operatorId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publishToNacos(type, target, operatorId);
            insertAuditLog(type.getId(), "ACTIVATE", target.getId(), target.getVersion(), operatorId, null);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publishToNacos(type, target, operatorId);
                insertAuditLog(type.getId(), "ACTIVATE", target.getId(), target.getVersion(), operatorId, null);
            }

            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    insertAuditLog(type.getId(), "ACTIVATE_ROLLBACK", target.getId(),
                            target.getVersion(), operatorId, "数据库事务未提交，激活已回滚");
                }
            }
        });
    }

    private void publishToNacos(ConfigType type, ConfigSnapshot target, String operatorId) {
        if (nacosConfigService == null) {
            insertAuditLog(type.getId(), "ACTIVATE_PUBLISH_FAILED", target.getId(),
                    target.getVersion(), operatorId, "Nacos Config 未启用或 ConfigService 未初始化");
            throw new ConfigPublishException("Nacos Config 未启用或 ConfigService 未初始化");
        }
        try {
            boolean success = nacosConfigService.publishConfig(
                    type.getDataId(), type.getConfigGroup(), target.getContentJson());
            if (!success) {
                insertAuditLog(type.getId(), "ACTIVATE_PUBLISH_FAILED", target.getId(),
                        target.getVersion(), operatorId, "Nacos publishConfig 返回 false");
                throw new ConfigPublishException("Nacos publishConfig 返回 false");
            }
        } catch (NacosException e) {
            insertAuditLog(type.getId(), "ACTIVATE_PUBLISH_FAILED", target.getId(),
                    target.getVersion(), operatorId, "Nacos 发布配置失败: " + e.getMessage());
            throw new ConfigPublishException("Nacos 发布配置失败", e);
        }
    }

    private void insertAuditLog(Integer typeId, String action, Integer snapshotId,
                                String baseVersion, String operatorId, String reason) {
        try {
            ConfigAuditLog auditLog = new ConfigAuditLog();
            auditLog.setTypeId(typeId);
            auditLog.setAction(action);
            auditLog.setSnapshotId(snapshotId);
            auditLog.setBaseVersion(baseVersion);
            auditLog.setOperatorId(operatorId);
            auditLog.setReason(reason);
            configAuditLogDao.insert(auditLog);
        } catch (Exception e) {
            log.warn("[ConfigProfileService] 审计日志写入失败 action={} snapshotId={}", action, snapshotId, e);
        }
    }
}
