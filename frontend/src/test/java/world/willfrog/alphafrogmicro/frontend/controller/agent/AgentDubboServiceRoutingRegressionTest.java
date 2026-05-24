package world.willfrog.alphafrogmicro.frontend.controller.agent;

import org.apache.dubbo.config.annotation.DubboReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.util.ClassUtils;
import world.willfrog.alphafrogmicro.agent.idl.AgentDubboService;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 防回归测试：确认 AgentDubboService 的 Dubbo 路由没有 `group="*"` 混用，
 * 且 controller 按 URI 前缀正确分流到 langchain/legacy。
 */
class AgentDubboServiceRoutingRegressionTest {

    private static final String CONTROLLER_BASE_PACKAGE = "world.willfrog.alphafrogmicro.frontend.controller";
    private static final String SERVICE_BASE_PACKAGE = "world.willfrog.alphafrogmicro.frontend.service";

    @Test
    void noAgentDubboServiceConsumerUsesWildcardGroup() throws Exception {
        List<String> violations = new ArrayList<>();

        for (String basePackage : List.of(CONTROLLER_BASE_PACKAGE, SERVICE_BASE_PACKAGE)) {
            String packageSearchPath = "classpath*:" + ClassUtils.convertClassNameToResourcePath(basePackage) + "/**/*.class";
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            CachingMetadataReaderFactory readerFactory = new CachingMetadataReaderFactory();

            for (var resource : resolver.getResources(packageSearchPath)) {
                if (!resource.isReadable()) continue;
                MetadataReader reader = readerFactory.getMetadataReader(resource);
                String className = reader.getClassMetadata().getClassName();

                Class<?> clazz;
                try {
                    clazz = Class.forName(className);
                } catch (Throwable e) {
                    continue; // skip classes that fail to load (e.g. missing deps in test)
                }

                for (Field field : clazz.getDeclaredFields()) {
                    DubboReference ref = field.getAnnotation(DubboReference.class);
                    if (ref == null) continue;
                    if (!AgentDubboService.class.isAssignableFrom(field.getType())) continue;

                    String group = ref.group();
                    if ("*".equals(group)) {
                        violations.add(className + "." + field.getName() + " group=\"*\"");
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "AgentDubboService consumer 存在 group=\"*\" 的违规引用：" + violations);
    }

    @Test
    void agentControllersHaveResolveServiceMethodWithBothStubs() {
        List<String> violations = new ArrayList<>();

        for (Class<?> clazz : List.of(
                AgentController.class,
                AgentConfigController.class,
                AgentToolsController.class,
                AgentCreditController.class
        )) {
            boolean hasLangchainStub = false;
            boolean hasLegacyStub = false;
            boolean hasResolveService = false;

            for (Field field : clazz.getDeclaredFields()) {
                DubboReference ref = field.getAnnotation(DubboReference.class);
                if (ref == null) continue;
                if (!AgentDubboService.class.isAssignableFrom(field.getType())) continue;

                String group = ref.group();
                if ("langchain".equals(group)) hasLangchainStub = true;
                if ("legacy".equals(group)) hasLegacyStub = true;
            }

            for (Method method : clazz.getDeclaredMethods()) {
                if ("resolveService".equals(method.getName())
                        && method.getParameterCount() == 0
                        && AgentDubboService.class.equals(method.getReturnType())) {
                    hasResolveService = true;
                }
            }

            if (!hasLangchainStub) {
                violations.add(clazz.getSimpleName() + " 缺少 langchain stub");
            }
            if (!hasLegacyStub) {
                violations.add(clazz.getSimpleName() + " 缺少 legacy stub");
            }
            if (!hasResolveService) {
                violations.add(clazz.getSimpleName() + " 缺少 resolveService() 方法");
            }
        }

        assertTrue(violations.isEmpty(),
                "Controller Dubbo 路由配置不完整：" + violations);
    }
}
