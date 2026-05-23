package world.willfrog.agentlangchain.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentAiServiceFactory;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agent.platform.service.AgentRunStateStore;
import world.willfrog.agentlangchain.tools.LangchainToolInvocationKeys;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

@Service
@Slf4j
public class LangchainLinearRunPipelineImpl implements LangchainLinearRunPipeline {

    private final LangchainLinearWorkflowExecutor workflowExecutor;
    private final AgentRunMapper runMapper;
    private final AgentEventService eventService;
    private final AgentAiServiceFactory aiServiceFactory;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<ToolProvider> toolProviderProvider;
    private final ObjectProvider<AgentRunStateStore> stateStoreProvider;
    private final Executor langchainRunTaskExecutor;

    public LangchainLinearRunPipelineImpl(LangchainLinearWorkflowExecutor workflowExecutor,
                                          AgentRunMapper runMapper,
                                          AgentEventService eventService,
                                          AgentAiServiceFactory aiServiceFactory,
                                          ObjectMapper objectMapper,
                                          ObjectProvider<ToolProvider> toolProviderProvider,
                                          ObjectProvider<AgentRunStateStore> stateStoreProvider,
                                          @Qualifier("agentLangchainRunTaskExecutor") Executor langchainRunTaskExecutor) {
        this.workflowExecutor = workflowExecutor;
        this.runMapper = runMapper;
        this.eventService = eventService;
        this.aiServiceFactory = aiServiceFactory;
        this.objectMapper = objectMapper;
        this.toolProviderProvider = toolProviderProvider;
        this.stateStoreProvider = stateStoreProvider;
        this.langchainRunTaskExecutor = langchainRunTaskExecutor;
    }

    @Override
    public void launchAsync(AgentRun run) {
        langchainRunTaskExecutor.execute(() -> executeRun(run));
    }

    void executeRun(AgentRun initialRun) {
        if (initialRun == null || isBlank(initialRun.getId())) {
            return;
        }
        AgentRun run = runMapper.findById(initialRun.getId());
        if (run == null) {
            log.warn("LangChain run not found, skip: {}", initialRun.getId());
            return;
        }
        String runId = run.getId();
        String userId = run.getUserId();
        try {
            AgentContext.setRunId(runId);
            AgentContext.setUserId(userId);
            if (!eventService.isRunnable(runId, userId)) {
                return;
            }
            runMapper.updateStatus(runId, userId, AgentRunStatus.EXECUTING);
            markRunStatus(runId, AgentRunStatus.EXECUTING);
            eventService.append(runId, userId, "EXECUTION_STARTED", Map.of(
                    "run_id", runId,
                    "engine", "agentLangchainService",
                    "workflow", "linear"
            ));

            String endpointName = eventService.extractEndpointName(run.getExt());
            String modelName = eventService.extractModelName(run.getExt());
            ChatModel model = aiServiceFactory.buildChatModel(endpointName, modelName);
            String userGoal = eventService.extractUserGoal(run.getExt());
            AgentEventService.RunConfig runConfig = eventService.extractRunConfig(run.getExt());
            AgentContext.setWebSearchEnabled(runConfig.webSearchEnabled());
            AgentContext.setWebSearchConfig(runConfig.webSearchConfig());

            List<ToolSpecification> toolSpecifications = resolveToolSpecifications(runConfig, userGoal);
            LangchainLinearWorkflowResult result = workflowExecutor.execute(LangchainLinearWorkflowRequest.builder()
                    .runId(runId)
                    .userId(userId)
                    .userGoal(userGoal)
                    .dialogueContext("")
                    .model(model)
                    .toolSpecifications(toolSpecifications)
                    .webSearchEnabled(runConfig.webSearchEnabled())
                    .codeInterpreterEnabled(runConfig.codeInterpreterEnabled())
                    .build());

            runMapper.updatePlanJson(runId, userId, writeJson(result.getPlan()));
            if (result.isSuccess()) {
                String snapshot = buildSnapshot(userGoal, result, AgentRunStatus.COMPLETED);
                runMapper.updateSnapshot(runId, userId, AgentRunStatus.COMPLETED, snapshot, true, null);
                markRunStatus(runId, AgentRunStatus.COMPLETED);
                eventService.append(runId, userId, "WORKFLOW_COMPLETED", Map.of(
                        "answer", result.getFinalAnswer(),
                        "toolCallsUsed", result.getToolCallsUsed(),
                        "engine", "agentLangchainService"
                ));
            } else {
                String snapshot = buildSnapshot(userGoal, result, AgentRunStatus.FAILED);
                runMapper.updateSnapshot(runId, userId, AgentRunStatus.FAILED, snapshot, true, result.getFailureReason());
                markRunStatus(runId, AgentRunStatus.FAILED);
                eventService.append(runId, userId, "WORKFLOW_FAILED", Map.of(
                        "reason", nvl(result.getFailureReason()),
                        "engine", "agentLangchainService"
                ));
            }
        } catch (Exception e) {
            log.error("LangChain linear run failed: runId={}", runId, e);
            runMapper.updateSnapshot(runId, userId, AgentRunStatus.FAILED, "{}", true, e.getMessage());
            markRunStatus(runId, AgentRunStatus.FAILED);
            eventService.append(runId, userId, "WORKFLOW_FAILED", Map.of(
                    "reason", nvl(e.getMessage()),
                    "engine", "agentLangchainService"
            ));
        } finally {
            AgentContext.clear();
        }
    }

    private List<ToolSpecification> resolveToolSpecifications(AgentEventService.RunConfig runConfig, String userGoal) {
        ToolProvider provider = toolProviderProvider.getIfAvailable();
        if (provider == null) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(LangchainToolInvocationKeys.WEB_SEARCH_ENABLED, runConfig.webSearchEnabled());
        params.put(LangchainToolInvocationKeys.CODE_INTERPRETER_ENABLED, runConfig.codeInterpreterEnabled());
        return provider.provideTools(ToolProviderRequest.builder()
                        .userMessage(UserMessage.from(nvl(userGoal)))
                        .invocationContext(InvocationContext.builder()
                                .userMessage(UserMessage.from(nvl(userGoal)))
                                .invocationParameters(InvocationParameters.from(params))
                                .timestampNow()
                                .build())
                        .build())
                .tools()
                .keySet()
                .stream()
                .toList();
    }

    private String buildSnapshot(String userGoal,
                                 LangchainLinearWorkflowResult result,
                                 AgentRunStatus status) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("user_goal", userGoal);
        snapshot.put("plan", result.getPlan());
        snapshot.put("completed_items", result.getCompletedTodos());
        snapshot.put("answer", nvl(result.getFinalAnswer()));
        snapshot.put("answer_markdown", nvl(result.getFinalAnswer()));
        snapshot.put("status", status.name());
        snapshot.put("failure_reason", nvl(result.getFailureReason()));
        snapshot.put("tool_calls_used", result.getToolCallsUsed());
        snapshot.put("engine", "agentLangchainService");
        return writeJson(snapshot);
    }

    private void markRunStatus(String runId, AgentRunStatus status) {
        AgentRunStateStore stateStore = stateStoreProvider.getIfAvailable();
        if (stateStore != null) {
            stateStore.markRunStatus(runId, status.name());
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }
}
