package world.willfrog.agentlangchain.orchestration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.entity.AgentRunMessage;
import world.willfrog.agent.platform.service.AgentContextCompressor;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agent.platform.service.AgentMessageService;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class LangchainFollowUpContextSupport {

    private final AgentMessageService messageService;
    private final AgentContextCompressor contextCompressor;
    private final AgentEventService eventService;

    public ExecutionContext resolve(AgentRun run) {
        String runId = run.getId();
        String baseGoal = eventService.extractUserGoal(run.getExt());
        AgentRunMessage latestUser = messageService.findLatestUserMessage(runId);
        String currentGoal = baseGoal;
        if (latestUser != null
                && AgentRunMessage.MSG_TYPE_FOLLOW_UP.equals(latestUser.getMsgType())
                && latestUser.getContent() != null
                && !latestUser.getContent().isBlank()) {
            currentGoal = latestUser.getContent().trim();
        }
        return new ExecutionContext(nvl(currentGoal), buildDialogueContext(runId, currentGoal));
    }

    private String buildDialogueContext(String runId, String currentUserGoal) {
        try {
            List<AgentRunMessage> messages = messageService.listMessages(runId);
            if (messages == null || messages.isEmpty()) {
                return "";
            }
            AgentContextCompressor.ContextBuildResult result =
                    contextCompressor.buildCompressedContext(messages, currentUserGoal);
            return result.text() == null ? "" : result.text();
        } catch (Exception e) {
            log.warn("Failed to build follow-up dialogue context for runId={}: {}", runId, e.getMessage());
            return "";
        }
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    public record ExecutionContext(String userGoal, String dialogueContext) {
    }
}
