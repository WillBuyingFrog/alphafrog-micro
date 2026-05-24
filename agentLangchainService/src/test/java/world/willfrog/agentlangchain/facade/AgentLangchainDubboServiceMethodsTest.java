package world.willfrog.agentlangchain.facade;

import org.apache.dubbo.config.annotation.DubboService;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLangchainDubboServiceMethodsTest {

    @Test
    void dubboMethodsAnnotationIncludesAllPublicServiceMethods() {
        DubboService annotation = AgentLangchainDubboServiceImpl.class.getAnnotation(DubboService.class);
        Set<String> declared = Arrays.stream(annotation.methods())
                .map(org.apache.dubbo.config.annotation.Method::name)
                .collect(Collectors.toSet());

        Set<String> overrides = Arrays.stream(AgentLangchainDubboServiceImpl.class.getDeclaredMethods())
                .filter(m -> !m.isSynthetic() && !"reject".equals(m.getName()))
                .filter(m -> m.getDeclaringClass() == AgentLangchainDubboServiceImpl.class)
                .map(java.lang.reflect.Method::getName)
                .collect(Collectors.toSet());

        for (String methodName : overrides) {
            assertTrue(declared.contains(methodName),
                    "Missing @DubboService method registration: " + methodName);
        }
        assertTrue(declared.containsAll(Set.of("listArtifacts", "downloadArtifact", "sendMessage")));
    }
}
