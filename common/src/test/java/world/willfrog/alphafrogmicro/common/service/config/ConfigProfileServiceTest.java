package world.willfrog.alphafrogmicro.common.service.config;

import com.alibaba.nacos.api.config.ConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    @BeforeEach
    void setUp() {
        configProfileService = new ConfigProfileService(
                configTypeDao,
                configSnapshotDao,
                configActiveDao,
                configAuditLogDao,
                new ObjectMapper(),
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
