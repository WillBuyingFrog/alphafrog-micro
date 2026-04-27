package world.willfrog.alphafrogmicro.common.config.nacos;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * NacosConfigBridge 单元测试
 */
class NacosConfigBridgeTest {

    private ObjectMapper objectMapper;
    private MockEnvironment environment;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        environment = new MockEnvironment();
    }

    // ==================== resolveSubscriptions 测试 ====================

    @Test
    void resolveSubscriptions_shouldParseMultipleSubscriptions() {
        environment.setProperty("alphafrog.config.nacos.enabled", "true");
        environment.setProperty("alphafrog.config.nacos.subscriptions[0].data-id", "code-refine.json");
        environment.setProperty("alphafrog.config.nacos.subscriptions[0].group", "alphafrog-config");
        environment.setProperty("alphafrog.config.nacos.subscriptions[0].target-file", "/app/config-dynamic/code-refine.json");
        environment.setProperty("alphafrog.config.nacos.subscriptions[1].data-id", "agent-llm.json");
        environment.setProperty("alphafrog.config.nacos.subscriptions[1].group", "alphafrog-config");
        environment.setProperty("alphafrog.config.nacos.subscriptions[1].target-file", "/app/config-dynamic/agent-llm.json");

        NacosConfigBridge bridge = new NacosConfigBridge(objectMapper, environment);
        List<NacosConfigBridge.Subscription> subscriptions = invokeResolveSubscriptions(bridge);

        assertEquals(2, subscriptions.size());
        assertEquals("code-refine.json", subscriptions.get(0).getDataId());
        assertEquals("/app/config-dynamic/code-refine.json", subscriptions.get(0).getTargetFile());
        assertEquals("agent-llm.json", subscriptions.get(1).getDataId());
        assertEquals("/app/config-dynamic/agent-llm.json", subscriptions.get(1).getTargetFile());
    }

    @Test
    void resolveSubscriptions_shouldFallbackToLegacy_whenSubscriptionsEmpty() {
        environment.setProperty("alphafrog.config.nacos.enabled", "true");
        environment.setProperty("alphafrog.config.nacos.data-id", "code-refine.json");
        environment.setProperty("alphafrog.config.nacos.group", "alphafrog-config");
        // agent.flow.code-refine.config-file 通过 @Value 注入字段，在纯 new 实例中不会自动设置
        // 需要通过反射注入
        NacosConfigBridge bridge = new NacosConfigBridge(objectMapper, environment);
        injectValueField(bridge, "dataId", "code-refine.json");
        injectValueField(bridge, "group", "alphafrog-config");
        injectValueField(bridge, "configFilePath", "/app/config-dynamic/code-refine.json");

        List<NacosConfigBridge.Subscription> subscriptions = invokeResolveSubscriptions(bridge);

        assertEquals(1, subscriptions.size());
        assertEquals("code-refine.json", subscriptions.get(0).getDataId());
        assertEquals("alphafrog-config", subscriptions.get(0).getGroup());
        assertEquals("/app/config-dynamic/code-refine.json", subscriptions.get(0).getTargetFile());
    }

    @Test
    void resolveSubscriptions_shouldFilterInvalidSubscriptions() {
        environment.setProperty("alphafrog.config.nacos.enabled", "true");
        // data-id 为空
        environment.setProperty("alphafrog.config.nacos.subscriptions[0].data-id", "");
        environment.setProperty("alphafrog.config.nacos.subscriptions[0].target-file", "/app/config-dynamic/test.json");
        // target-file 为空
        environment.setProperty("alphafrog.config.nacos.subscriptions[1].data-id", "valid.json");
        environment.setProperty("alphafrog.config.nacos.subscriptions[1].target-file", "");
        // 有效
        environment.setProperty("alphafrog.config.nacos.subscriptions[2].data-id", "valid.json");
        environment.setProperty("alphafrog.config.nacos.subscriptions[2].target-file", "/app/config-dynamic/valid.json");

        NacosConfigBridge bridge = new NacosConfigBridge(objectMapper, environment);
        List<NacosConfigBridge.Subscription> subscriptions = invokeResolveSubscriptions(bridge);

        assertEquals(1, subscriptions.size());
        assertEquals("valid.json", subscriptions.get(0).getDataId());
    }

    @Test
    void resolveSubscriptions_shouldUseDefaultGroup_whenGroupBlank() {
        environment.setProperty("alphafrog.config.nacos.enabled", "true");
        environment.setProperty("alphafrog.config.nacos.group", "default-group");
        environment.setProperty("alphafrog.config.nacos.subscriptions[0].data-id", "test.json");
        environment.setProperty("alphafrog.config.nacos.subscriptions[0].target-file", "/app/config-dynamic/test.json");
        // group 未设置

        NacosConfigBridge bridge = new NacosConfigBridge(objectMapper, environment);
        injectValueField(bridge, "group", "default-group");

        List<NacosConfigBridge.Subscription> subscriptions = invokeResolveSubscriptions(bridge);

        assertEquals(1, subscriptions.size());
        assertEquals("default-group", subscriptions.get(0).getGroup());
    }

    // ==================== writeConfigToFile 测试 ====================

    @Test
    void writeConfigToFile_shouldWriteValidJson(@TempDir Path tempDir) throws Exception {
        NacosConfigBridge bridge = new NacosConfigBridge(objectMapper, environment);
        Path targetFile = tempDir.resolve("test-config.json");
        NacosConfigBridge.Subscription sub = new NacosConfigBridge.Subscription();
        sub.setDataId("test.json");
        sub.setGroup("test-group");
        sub.setTargetFile(targetFile.toString());

        String jsonContent = "{\"key\":\"value\",\"number\":42}";
        invokeWriteConfigToFile(bridge, sub, jsonContent);

        assertTrue(Files.exists(targetFile));
        String readBack = Files.readString(targetFile);
        assertEquals(jsonContent, readBack);
    }

    @Test
    void writeConfigToFile_shouldRejectInvalidJson(@TempDir Path tempDir) throws Exception {
        NacosConfigBridge bridge = new NacosConfigBridge(objectMapper, environment);
        Path targetFile = tempDir.resolve("test-config.json");
        NacosConfigBridge.Subscription sub = new NacosConfigBridge.Subscription();
        sub.setDataId("test.json");
        sub.setGroup("test-group");
        sub.setTargetFile(targetFile.toString());

        String invalidJson = "{\"key\":\"value\""; // 不完整的 JSON
        invokeWriteConfigToFile(bridge, sub, invalidJson);

        // 无效 JSON 应在移动到目标文件前被拒绝，避免生成损坏配置。
        assertFalse(Files.exists(targetFile));
    }

    @Test
    void writeConfigToFile_shouldBackupExistingFile(@TempDir Path tempDir) throws Exception {
        NacosConfigBridge bridge = new NacosConfigBridge(objectMapper, environment);
        Path targetFile = tempDir.resolve("test-config.json");
        String oldContent = "{\"old\":true}";
        Files.writeString(targetFile, oldContent);

        NacosConfigBridge.Subscription sub = new NacosConfigBridge.Subscription();
        sub.setDataId("test.json");
        sub.setGroup("test-group");
        sub.setTargetFile(targetFile.toString());

        String newContent = "{\"new\":true}";
        invokeWriteConfigToFile(bridge, sub, newContent);

        String readBack = Files.readString(targetFile);
        assertEquals(newContent, readBack);

        // 验证备份文件存在
        List<Path> backups = Files.list(tempDir)
                .filter(p -> p.getFileName().toString().startsWith("test-config.json.backup."))
                .toList();
        assertEquals(1, backups.size());
        String backupContent = Files.readString(backups.get(0));
        assertEquals(oldContent, backupContent);
    }

    @Test
    void writeConfigToFile_shouldRestoreBackupOnFailure(@TempDir Path tempDir) throws Exception {
        NacosConfigBridge bridge = new NacosConfigBridge(objectMapper, environment);
        Path targetFile = tempDir.resolve("test-config.json");
        String oldContent = "{\"old\":true}";
        Files.writeString(targetFile, oldContent);

        NacosConfigBridge.Subscription sub = new NacosConfigBridge.Subscription();
        sub.setDataId("test.json");
        sub.setGroup("test-group");
        sub.setTargetFile(targetFile.toString());

        String invalidJson = "{\"broken\":"; // 无效 JSON，readTree 会失败
        invokeWriteConfigToFile(bridge, sub, invalidJson);

        // 失败后应该还原备份
        String readBack = Files.readString(targetFile);
        assertEquals(oldContent, readBack);
    }

    // ==================== init 测试（mock ConfigService） ====================

    @Test
    void init_shouldGetInitialConfigAndRegisterListener(@TempDir Path tempDir) throws Exception {
        environment.setProperty("alphafrog.config.nacos.enabled", "true");
        environment.setProperty("alphafrog.config.nacos.server-addr", "127.0.0.1:8848");
        Path targetFile = tempDir.resolve("nacos-config.json");
        environment.setProperty("alphafrog.config.nacos.subscriptions[0].data-id", "nacos-config.json");
        environment.setProperty("alphafrog.config.nacos.subscriptions[0].target-file", targetFile.toString());

        NacosConfigBridge bridge = new NacosConfigBridge(objectMapper, environment);
        ConfigService mockConfigService = mock(ConfigService.class);
        when(mockConfigService.getConfig(anyString(), anyString(), anyLong()))
                .thenReturn("{\"from\":\"nacos\"}");

        // 将 mock 的 ConfigService 注入
        java.lang.reflect.Field configServiceField = NacosConfigBridge.class.getDeclaredField("configService");
        configServiceField.setAccessible(true);
        configServiceField.set(bridge, mockConfigService);

        java.lang.reflect.Method subscribeMethod = NacosConfigBridge.class.getDeclaredMethod(
                "subscribe", NacosConfigBridge.Subscription.class);
        subscribeMethod.setAccessible(true);

        NacosConfigBridge.Subscription sub = new NacosConfigBridge.Subscription();
        sub.setDataId("nacos-config.json");
        sub.setGroup("alphafrog-config");
        sub.setTargetFile(targetFile.toString());
        subscribeMethod.invoke(bridge, sub);

        // 验证 getConfig 被调用
        verify(mockConfigService).getConfig("nacos-config.json", "alphafrog-config", 5000L);
        // 验证 listener 被注册
        ArgumentCaptor<Listener> listenerCaptor = ArgumentCaptor.forClass(Listener.class);
        verify(mockConfigService).addListener(eq("nacos-config.json"), eq("alphafrog-config"), listenerCaptor.capture());

        // 验证初始配置已写入文件
        assertTrue(Files.exists(targetFile));
        assertEquals("{\"from\":\"nacos\"}", Files.readString(targetFile));

        // 模拟 Nacos 推送
        Listener listener = listenerCaptor.getValue();
        listener.receiveConfigInfo("{\"updated\":true}");

        // 验证更新后的配置已写入
        assertEquals("{\"updated\":true}", Files.readString(targetFile));
    }

    // ==================== 反射辅助方法 ====================

    @SuppressWarnings("unchecked")
    private List<NacosConfigBridge.Subscription> invokeResolveSubscriptions(NacosConfigBridge bridge) {
        try {
            java.lang.reflect.Method method = NacosConfigBridge.class.getDeclaredMethod("resolveSubscriptions");
            method.setAccessible(true);
            return (List<NacosConfigBridge.Subscription>) method.invoke(bridge);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void invokeWriteConfigToFile(NacosConfigBridge bridge, NacosConfigBridge.Subscription sub, String content) {
        try {
            java.lang.reflect.Method method = NacosConfigBridge.class.getDeclaredMethod(
                    "writeConfigToFile", NacosConfigBridge.Subscription.class, String.class);
            method.setAccessible(true);
            method.invoke(bridge, sub, content);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void injectValueField(NacosConfigBridge bridge, String fieldName, String value) {
        try {
            java.lang.reflect.Field field = NacosConfigBridge.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(bridge, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
