package world.willfrog.alphafrogmicro.common.service.config;

import com.alibaba.nacos.api.config.ConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import world.willfrog.alphafrogmicro.common.config.ConfigJsonCanonicalizer;
import world.willfrog.alphafrogmicro.common.dao.config.ConfigActiveDao;
import world.willfrog.alphafrogmicro.common.dao.config.ConfigAuditLogDao;
import world.willfrog.alphafrogmicro.common.dao.config.ConfigSnapshotDao;
import world.willfrog.alphafrogmicro.common.dao.config.ConfigTypeDao;
import world.willfrog.alphafrogmicro.common.exception.config.ConfigConflictException;
import world.willfrog.alphafrogmicro.common.pojo.config.ConfigActive;
import world.willfrog.alphafrogmicro.common.pojo.config.ConfigAuditLog;
import world.willfrog.alphafrogmicro.common.pojo.config.ConfigSnapshot;
import world.willfrog.alphafrogmicro.common.pojo.config.ConfigType;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfigProfileServiceTest {

    @Mock
    private ConfigTypeDao configTypeDao;

    @Mock
    private ConfigSnapshotDao configSnapshotDao;

    @Mock
    private ConfigActiveDao configActiveDao;

    @Mock
    private ConfigAuditLogDao configAuditLogDao;

    @Mock
    private ConfigService nacosConfigService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private ConfigProfileService configProfileService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        configProfileService = new ConfigProfileService(
                configTypeDao,
                configSnapshotDao,
                configActiveDao,
                configAuditLogDao,
                objectMapper,
                nacosConfigService,
                redisTemplate
        );
    }

    @Test
    void getActiveWithReplicasShouldReturnUnsyncedWhenReplicaListIsEmpty() {
        ConfigType type = buildType();
        ConfigSnapshot snapshot = buildSnapshot();
        ConfigActive active = new ConfigActive();
        active.setTypeId(type.getId());
        active.setSnapshotId(snapshot.getId());

        when(configTypeDao.getByName("code-refine")).thenReturn(type);
        when(configActiveDao.getByType(type.getId())).thenReturn(active);
        when(configSnapshotDao.getById(snapshot.getId())).thenReturn(snapshot);
        when(redisTemplate.keys("config:state:*:*:code-refine.json")).thenReturn(Collections.emptySet());

        Map<String, Object> result = configProfileService.getActiveWithReplicas("code-refine");

        assertEquals(0, result.get("replicaCount"));
        assertFalse((Boolean) result.get("synced"));
    }

    @Test
    void getActiveWithReplicasShouldExposeInstanceIdAndSyncedState() throws Exception {
        ConfigType type = buildType();
        ConfigSnapshot snapshot = buildSnapshot();
        ConfigActive active = new ConfigActive();
        active.setTypeId(type.getId());
        active.setSnapshotId(snapshot.getId());

        when(configTypeDao.getByName("code-refine")).thenReturn(type);
        when(configActiveDao.getByType(type.getId())).thenReturn(active);
        when(configSnapshotDao.getById(snapshot.getId())).thenReturn(snapshot);
        when(redisTemplate.keys("config:state:*:*:code-refine.json"))
                .thenReturn(Set.of("config:state:agent-service:pod-1:code-refine.json"));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("config:state:agent-service:pod-1:code-refine.json"))
                .thenReturn("{\"md5\":\"" + ConfigJsonCanonicalizer.md5Hex(snapshot.getContentJson())
                        + "\",\"loadedAt\":\"2026-04-24T10:00:00Z\"}");

        Map<String, Object> result = configProfileService.getActiveWithReplicas("code-refine");

        assertEquals(1, result.get("replicaCount"));
        assertTrue((Boolean) result.get("synced"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> replicas = (List<Map<String, Object>>) result.get("replicas");
        assertEquals("agent-service", replicas.get(0).get("serviceName"));
        assertEquals("pod-1", replicas.get(0).get("instanceId"));
    }

    @Test
    void getActiveWithReplicasShouldCompareCanonicalMd5ForLegacySnapshot() throws Exception {
        ConfigType type = buildType();
        ConfigSnapshot snapshot = buildSnapshot();
        snapshot.setContentJson("{\"b\":2,\"a\":{\"d\":4,\"c\":3}}");
        snapshot.setContentMd5("legacy-md5");
        ConfigActive active = new ConfigActive();
        active.setTypeId(type.getId());
        active.setSnapshotId(snapshot.getId());

        String replicaMd5 = ConfigJsonCanonicalizer.md5Hex("{\"a\":{\"c\":3,\"d\":4},\"b\":2}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(configTypeDao.getByName("code-refine")).thenReturn(type);
        when(configActiveDao.getByType(type.getId())).thenReturn(active);
        when(configSnapshotDao.getById(snapshot.getId())).thenReturn(snapshot);
        when(redisTemplate.keys("config:state:*:*:code-refine.json"))
                .thenReturn(Set.of("config:state:agent-service:pod-1:code-refine.json"));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("config:state:agent-service:pod-1:code-refine.json"))
                .thenReturn("{\"md5\":\"" + replicaMd5 + "\",\"loadedAt\":\"2026-04-24T10:00:00Z\"}");

        Map<String, Object> result = configProfileService.getActiveWithReplicas("code-refine");

        assertTrue((Boolean) result.get("synced"));
        assertEquals(replicaMd5, result.get("activeContentMd5"));
        assertEquals(replicaMd5, ((ConfigSnapshot) result.get("activeSnapshot")).getContentMd5());
    }

    @Test
    void activateShouldThrowConflictWhenAtomicUpdateFails() {
        ConfigType type = buildType();
        ConfigSnapshot snapshot = buildSnapshot();

        when(configTypeDao.getByName("code-refine")).thenReturn(type);
        when(configSnapshotDao.getByTypeAndVersion(type.getId(), "v2")).thenReturn(snapshot);
        when(configActiveDao.updateIfSnapshotMatches(eq(type.getId()), eq(snapshot.getId()), eq(1), any(), eq("7")))
                .thenReturn(0);

        assertThrows(ConfigConflictException.class,
                () -> configProfileService.activate("code-refine", "v2", 1, "7"));

        verifyNoInteractions(nacosConfigService);
    }

    @Test
    void activateShouldPublishConfigAfterAtomicUpdateSucceeds() throws Exception {
        ConfigType type = buildType();
        ConfigSnapshot snapshot = buildSnapshot();

        when(configTypeDao.getByName("code-refine")).thenReturn(type);
        when(configSnapshotDao.getByTypeAndVersion(type.getId(), "v2")).thenReturn(snapshot);
        when(configActiveDao.updateIfSnapshotMatches(eq(type.getId()), eq(snapshot.getId()), eq(1), any(), eq("7")))
                .thenReturn(1);
        when(nacosConfigService.publishConfig(type.getDataId(), type.getConfigGroup(), snapshot.getContentJson()))
                .thenReturn(true);

        configProfileService.activate("code-refine", "v2", 1, "7");

        verify(nacosConfigService).publishConfig(type.getDataId(), type.getConfigGroup(), snapshot.getContentJson());
        verify(configAuditLogDao).insert(any(ConfigAuditLog.class));
    }

    @Test
    void deriveOpsShouldSupportArrayElementOperations() throws Exception {
        ConfigSnapshot inserted = deriveAndCapture(
                "{\"models\":[\"a\",\"b\"]}",
                """
                        [
                          {"op":"add_if_absent","path":"/models","value":"c"},
                          {"op":"add_if_absent","path":"/models","value":"c"},
                          {"op":"add","path":"/models/1","value":"x"},
                          {"op":"set","path":"/models/0","value":"aa"},
                          {"op":"remove","path":"/models/2"}
                        ]
                        """
        );

        JsonNode content = objectMapper.readTree(inserted.getContentJson());
        assertEquals(List.of("aa", "x", "c"), objectMapper.convertValue(content.get("models"), List.class));
    }

    @Test
    void deriveOpsShouldSupportArrayAppendAndRemoveValue() throws Exception {
        ConfigSnapshot inserted = deriveAndCapture(
                "{\"models\":[\"a\",\"b\",\"c\"]}",
                """
                        [
                          {"op":"add","path":"/models/-","value":"d"},
                          {"op":"remove_value","path":"/models","value":"b"}
                        ]
                        """
        );

        JsonNode content = objectMapper.readTree(inserted.getContentJson());
        assertEquals(List.of("a", "c", "d"), objectMapper.convertValue(content.get("models"), List.class));
    }

    @Test
    void deriveOpsShouldKeepLegacyArrayAppendAndRemoveByValue() throws Exception {
        ConfigSnapshot inserted = deriveAndCapture(
                "{\"models\":[\"a\",\"b\"]}",
                """
                        [
                          {"op":"add","path":"/models","value":"c"},
                          {"op":"remove","path":"/models","value":"a"}
                        ]
                        """
        );

        JsonNode content = objectMapper.readTree(inserted.getContentJson());
        assertEquals(List.of("b", "c"), objectMapper.convertValue(content.get("models"), List.class));
    }

    @Test
    void deriveOpsShouldSupportEscapedJsonPointerSegments() throws Exception {
        ConfigSnapshot inserted = deriveAndCapture(
                "{\"endpoints\":{\"openrouter\":{\"models\":{}}}}",
                """
                        [
                          {
                            "op":"set",
                            "path":"/endpoints/openrouter/models/your-provider~1your-new-model",
                            "value":{"baseRate":0.2,"displayName":"Your New Model","validProviders":["your-provider"]}
                          }
                        ]
                        """
        );

        JsonNode content = objectMapper.readTree(inserted.getContentJson());
        JsonNode model = content.at("/endpoints/openrouter/models/your-provider~1your-new-model");
        assertEquals("Your New Model", model.get("displayName").asText());
        assertEquals(0.2, model.get("baseRate").asDouble(), 0.0001);
    }

    @Test
    void deriveOpsShouldRejectInvalidArrayIndex() throws Exception {
        ConfigType type = buildType();
        ConfigSnapshot base = buildSnapshot();
        base.setVersion("v1");
        base.setContentJson("{\"models\":[\"a\"]}");

        when(configTypeDao.getByName("code-refine")).thenReturn(type);
        when(configSnapshotDao.getByTypeAndVersion(type.getId(), "v1")).thenReturn(base);

        assertThrows(IllegalArgumentException.class, () -> configProfileService.derive(
                "code-refine",
                "v1",
                "ops",
                objectMapper.readTree("[{\"op\":\"set\",\"path\":\"/models/2\",\"value\":\"x\"}]"),
                "bad index",
                "7",
                true
        ));
    }

    private ConfigSnapshot deriveAndCapture(String baseJson, String patchJson) throws Exception {
        ConfigType type = buildType();
        ConfigSnapshot base = buildSnapshot();
        base.setId(1);
        base.setVersion("v1");
        base.setContentJson(baseJson);

        when(configTypeDao.getByName("code-refine")).thenReturn(type);
        when(configSnapshotDao.getByTypeAndVersion(type.getId(), "v1")).thenReturn(base);
        when(configSnapshotDao.maxVersionNumberByType(type.getId())).thenReturn(1);

        configProfileService.derive(
                "code-refine",
                "v1",
                "ops",
                objectMapper.readTree(patchJson),
                "test ops",
                "7",
                true
        );

        ArgumentCaptor<ConfigSnapshot> captor = ArgumentCaptor.forClass(ConfigSnapshot.class);
        verify(configSnapshotDao).insert(captor.capture());
        return captor.getValue();
    }

    private ConfigType buildType() {
        ConfigType type = new ConfigType();
        type.setId(1);
        type.setName("code-refine");
        type.setDataId("code-refine.json");
        type.setConfigGroup("alphafrog-config");
        return type;
    }

    private ConfigSnapshot buildSnapshot() {
        ConfigSnapshot snapshot = new ConfigSnapshot();
        snapshot.setId(2);
        snapshot.setTypeId(1);
        snapshot.setVersion("v2");
        snapshot.setContentJson("{\"maxAttempts\":5}");
        snapshot.setContentMd5("abc123");
        return snapshot;
    }
}
