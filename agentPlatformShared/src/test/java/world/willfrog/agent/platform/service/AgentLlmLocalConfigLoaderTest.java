package world.willfrog.agent.platform.service;

import world.willfrog.agent.platform.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.AgentLlmProperties;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLlmLocalConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void load_shouldParseNewExecutionJudgeAndSemanticPromptFields() throws Exception {
        Path promptsDir = tempDir.resolve("prompts").resolve("judge");
        Files.createDirectories(promptsDir);
        Path semanticPromptFile = promptsDir.resolve("semantic_judge_system.txt");
        Files.writeString(semanticPromptFile, "semantic prompt v1", StandardCharsets.UTF_8);

        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {
                  "runtime": {
                    "execution": {
                      "staticPrecheckEnabled": true,
                      "maxStaticRecoveryRetries": 2,
                      "maxRuntimeRecoveryRetries": 3,
                      "maxSemanticRecoveryRetries": 1,
                      "maxTotalRecoveryRetries": 4,
                      "staticFixEndpoint": "openrouter",
                      "staticFixModel": "openai/gpt-5.2",
                      "staticFixTemperature": 0.0
                    },
                    "judge": {
                      "semanticEnabled": true,
                      "maxAttempts": 3,
                      "failOpen": false,
                      "blockOnInsufficientEvidence": true,
                      "routes": [
                        {
                          "endpointName": "openrouter",
                          "models": ["openai/gpt-5.2"]
                        }
                      ]
                    }
                  },
                  "prompts": {
                    "semanticJudgeSystemPromptTemplate": "file:prompts/judge/semantic_judge_system.txt"
                  }
                }
                """, StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();

        var local = loader.current().orElseThrow();
        var execution = local.getRuntime().getExecution();
        var judge = local.getRuntime().getJudge();

        assertTrue(Boolean.TRUE.equals(execution.getStaticPrecheckEnabled()));
        assertEquals(2, execution.getMaxStaticRecoveryRetries());
        assertEquals(3, execution.getMaxRuntimeRecoveryRetries());
        assertEquals(1, execution.getMaxSemanticRecoveryRetries());
        assertEquals(4, execution.getMaxTotalRecoveryRetries());
        assertEquals("openrouter", execution.getStaticFixEndpoint());
        assertEquals("openai/gpt-5.2", execution.getStaticFixModel());
        assertEquals(0.0D, execution.getStaticFixTemperature());

        assertTrue(Boolean.TRUE.equals(judge.getSemanticEnabled()));
        assertEquals(3, judge.getMaxAttempts());
        assertFalse(Boolean.TRUE.equals(judge.getFailOpen()));
        assertTrue(Boolean.TRUE.equals(judge.getBlockOnInsufficientEvidence()));
        assertEquals(1, judge.getRoutes().size());
        assertEquals("openrouter", judge.getRoutes().get(0).getEndpointName());
        assertEquals("openai/gpt-5.2", judge.getRoutes().get(0).getModels().get(0));

        assertEquals("semantic prompt v1", local.getPrompts().getSemanticJudgeSystemPromptTemplate());
    }

    @Test
    void refresh_shouldReloadWhenSemanticPromptFileChanges() throws Exception {
        Path promptsDir = tempDir.resolve("prompts").resolve("judge");
        Files.createDirectories(promptsDir);
        Path semanticPromptFile = promptsDir.resolve("semantic_judge_system.txt");
        Files.writeString(semanticPromptFile, "semantic prompt v1", StandardCharsets.UTF_8);

        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {
                  "runtime": {
                    "execution": { "staticPrecheckEnabled": true },
                    "judge": { "semanticEnabled": true }
                  },
                  "prompts": {
                    "semanticJudgeSystemPromptTemplate": "file:prompts/judge/semantic_judge_system.txt"
                  }
                }
                """, StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();
        assertEquals("semantic prompt v1", loader.current().orElseThrow().getPrompts().getSemanticJudgeSystemPromptTemplate());

        Thread.sleep(5L);
        Files.writeString(semanticPromptFile, "semantic prompt v2", StandardCharsets.UTF_8);
        loader.refresh();

        assertEquals("semantic prompt v2", loader.current().orElseThrow().getPrompts().getSemanticJudgeSystemPromptTemplate());
    }

    @Test
    void load_shouldDefaultRequiresAdjFactorEnabledToFalseWhenOmitted() throws Exception {
        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {
                  "runtime": {
                    "parallel": {
                      "toolWeightedLimit": {
                        "enabled": true,
                        "tools": {
                          "getStockDaily": { "weight": 2 },
                          "getEtfAdj": { "weight": 2, "requiresAdjFactorEnabled": true }
                        }
                      }
                    }
                  }
                }
                """, StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();

        var tools = loader.current().orElseThrow()
                .getRuntime().getParallel().getToolWeightedLimit().getTools();
        assertFalse(tools.get("getStockDaily").isRequiresAdjFactorEnabled());
        assertFalse(tools.get("getStockDaily").getRequiresAdjFactorEnabled());
        assertTrue(tools.get("getEtfAdj").isRequiresAdjFactorEnabled());
    }

    @Test
    void load_shouldParseRunBudgetFields() throws Exception {
        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {
                  "runtime": {
                    "runBudget": {
                      "maxWallClockMs": 120000,
                      "maxLlmCalls": 10,
                      "maxToolCalls": 60,
                      "maxTokens": 50000,
                      "maxHttpAttemptsPerLogicalCall": 3
                    }
                  }
                }
                """, StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();

        AgentLlmProperties.RunBudget runBudget = loader.current().orElseThrow()
                .getRuntime().getRunBudget();
        assertEquals(120000L, runBudget.getMaxWallClockMs());
        assertEquals(10L, runBudget.getMaxLlmCalls());
        assertEquals(60L, runBudget.getMaxToolCalls());
        assertEquals(50000L, runBudget.getMaxTokens());
        assertEquals(3, runBudget.getMaxHttpAttemptsPerLogicalCall());
    }

    @Test
    void load_shouldResolveDagModeGuidancePromptFile() throws Exception {
        Path promptsDir = tempDir.resolve("prompts").resolve("todo");
        Files.createDirectories(promptsDir);
        Path guidanceFile = promptsDir.resolve("dag_mode_guidance.txt");
        Files.writeString(guidanceFile, "dag guidance v1", StandardCharsets.UTF_8);

        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {
                  "prompts": {
                    "dagModeGuidancePromptFile": "file:prompts/todo/dag_mode_guidance.txt"
                  }
                }
                """, StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();

        var local = loader.current().orElseThrow();
        assertEquals("dag guidance v1", local.getPrompts().getDagModeGuidancePromptFile());
    }
}
