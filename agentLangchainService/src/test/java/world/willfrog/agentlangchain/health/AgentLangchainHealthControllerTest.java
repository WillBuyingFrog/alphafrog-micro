package world.willfrog.agentlangchain.health;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import world.willfrog.agentlangchain.config.LangchainAiServiceConfig;
import world.willfrog.agentlangchain.config.LangchainServiceProperties;
import world.willfrog.agentlangchain.orchestration.AgentLangchainOrchestrator;
import world.willfrog.agentlangchain.orchestration.UnsupportedOrchestrationService;
import world.willfrog.agentlangchain.routing.LangchainTrafficRouter;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentLangchainHealthController.class)
@Import({
        LangchainAiServiceConfig.class,
        UnsupportedOrchestrationService.class,
        AgentLangchainOrchestrator.class,
        LangchainTrafficRouter.class
})
class AgentLangchainHealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthReportsDisabledProviderAndSharedModules() throws Exception {
        mockMvc.perform(get("/agent-langchain/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service", is("agentLangchainService")))
                .andExpect(jsonPath("$.providerEnabled", is(false)))
                .andExpect(jsonPath("$.platformSharedLoaded", is(true)))
                .andExpect(jsonPath("$.toolsSharedLoaded", is(true)))
                .andExpect(jsonPath("$.status", is("UP")));
    }
}
