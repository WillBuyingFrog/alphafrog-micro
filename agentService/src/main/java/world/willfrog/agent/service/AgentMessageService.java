package world.willfrog.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.entity.AgentRunMessage;
import world.willfrog.agent.mapper.AgentRunMessageMapper;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Agent 消息服务。
 * <p>
 * 职责：
 * 1. 消息的 CRUD 操作
 * 2. 消息序号的生成（带冲突重试）
 * 3. 消息历史的查询（支持分页）
 * 4. 初始消息和助手回复的落库
 *
 * @author kimi
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentMessageService {

    private final AgentRunMessageMapper messageMapper;

    // 最大重试次数（处理 seq 冲突）
    private static final int MAX_RETRY = 3;

    /**
     * 创建初始用户消息（CreateRun 时调用）。
     *
     * @param runId   Run ID
     * @param content 消息内容
     * @return 创建的消息 ID
     */
    public Long createInitialMessage(String runId, String content) {
        return createInitialMessage(runId, content, null);
    }

    /**
     * 创建初始用户消息（CreateRun 时调用）。
     *
     * @param runId    Run ID
     * @param content  消息内容
     * @param metaJson 元数据 JSON（可为 null）
     * @return 创建的消息 ID
     */
    public Long createInitialMessage(String runId, String content, String metaJson) {
        return createMessageWithRetry(runId, AgentRunMessage.ROLE_USER, content,
                AgentRunMessage.MSG_TYPE_INITIAL, metaJson);
    }

    /**
     * 创建用户追问消息（SendMessage 时调用）。
     *
     * @param runId   Run ID
     * @param content 消息内容
     * @return 创建的消息实体（包含分配的 seq）
     */
    public AgentRunMessage createUserMessage(String runId, String content) {
        return createUserMessage(runId, content, null);
    }

    /**
     * 创建用户追问消息（SendMessage 时调用）。
     *
     * @param runId    Run ID
     * @param content  消息内容
     * @param metaJson 元数据 JSON（可为 null）
     * @return 创建的消息实体（包含分配的 seq）
     */
    public AgentRunMessage createUserMessage(String runId, String content, String metaJson) {
        Long id = createMessageWithRetry(runId, AgentRunMessage.ROLE_USER, content,
                AgentRunMessage.MSG_TYPE_FOLLOW_UP, metaJson);
        return messageMapper.findById(id);
    }

    /**
     * 创建助手回复消息（Run 完成时调用）。
     *
     * @param runId   Run ID
     * @param content 消息内容
     * @return 创建的消息 ID
     */
    public Long createAssistantMessage(String runId, String content) {
        return createAssistantMessage(runId, content, null);
    }

    /**
     * 创建助手回复消息（Run 完成时调用）。
     *
     * @param runId    Run ID
     * @param content  消息内容
     * @param metaJson 元数据 JSON（包含 model, tokens, endpoint 等）
     * @return 创建的消息 ID
     */
    public Long createAssistantMessage(String runId, String content, String metaJson) {
        return createMessageWithRetry(runId, AgentRunMessage.ROLE_ASSISTANT, content,
                AgentRunMessage.MSG_TYPE_FOLLOW_UP, metaJson);
    }

    /**
     * 创建系统摘要消息（上下文压缩时使用）。
     *
     * @param runId   Run ID
     * @param content 摘要内容
     * @return 创建的消息 ID
     */
    public Long createSummaryMessage(String runId, String content) {
        return createMessageWithRetry(runId, AgentRunMessage.ROLE_SYSTEM, content,
                AgentRunMessage.MSG_TYPE_SUMMARY, null);
    }

    /**
     * 带重试的消息创建（处理 seq 冲突）。
     *
     * @param runId    Run ID
     * @param role     角色
     * @param content  内容
     * @param msgType  消息类型
     * @param metaJson 元数据
     * @return 创建的消息 ID
     */
    private Long createMessageWithRetry(String runId, String role, String content,
                                        String msgType, String metaJson) {
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                Integer nextSeq = messageMapper.getNextSeq(runId);
                if (nextSeq == null) {
                    nextSeq = 1;
                }

                AgentRunMessage message = new AgentRunMessage();
                message.setRunId(runId);
                message.setSeq(nextSeq);
                message.setRole(role);
                message.setContent(content);
                message.setMsgType(msgType);
                message.setMetaJson(metaJson != null ? metaJson : "{}");
                message.setCreatedAt(OffsetDateTime.now());

                messageMapper.insert(message);
                return message.getId();
            } catch (Exception e) {
                if (attempt == MAX_RETRY) {
                    log.error("Failed to create message after {} retries, runId={}", MAX_RETRY, runId, e);
                    throw new IllegalStateException("Failed to create message: " + e.getMessage(), e);
                }
                log.warn("Message creation conflict, retrying {}/{}, runId={}", attempt, MAX_RETRY, runId);
                // 短暂延迟后重试
                try {
                    Thread.sleep(10L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while retrying", ie);
                }
            }
        }
        throw new IllegalStateException("Unexpected: should have thrown after max retries");
    }

    /**
     * 查询指定 run 的所有消息（按 seq 升序）。
     *
     * @param runId Run ID
     * @return 消息列表
     */
    public List<AgentRunMessage> listMessages(String runId) {
        return messageMapper.listByRunId(runId);
    }

    /**
     * 分页查询指定 run 的消息。
     *
     * @param runId  Run ID
     * @param limit  每页数量
     * @param offset 偏移量
     * @return 消息列表
     */
    public List<AgentRunMessage> listMessagesWithPagination(String runId, int limit, int offset) {
        return messageMapper.listByRunIdWithPagination(runId, limit, offset);
    }

    /**
     * 查询指定 run 的消息总数。
     *
     * @param runId Run ID
     * @return 消息数量
     */
    public int countMessages(String runId) {
        return messageMapper.countByRunId(runId);
    }

    /**
     * 查询指定 run 的最新消息。
     *
     * @param runId Run ID
     * @return 最新消息，如果没有则返回 null
     */
    public AgentRunMessage findLatestMessage(String runId) {
        return messageMapper.findLatestByRunId(runId);
    }

    /**
     * 查询指定 run 的最新用户消息。
     *
     * @param runId Run ID
     * @return 最新用户消息，如果没有则返回 null
     */
    public AgentRunMessage findLatestUserMessage(String runId) {
        return messageMapper.findLatestUserByRunId(runId);
    }

    /**
     * 查询指定 run 的最新 N 条消息（用于上下文压缩）。
     *
     * @param runId Run ID
     * @param n     数量
     * @return 消息列表（按 seq 升序）
     */
    public List<AgentRunMessage> findLatestNMessages(String runId, int n) {
        int total = countMessages(runId);
        if (total <= n) {
            return listMessages(runId);
        }
        int offset = total - n;
        return messageMapper.listByRunIdWithPagination(runId, n, offset);
    }

    /**
     * 获取指定 run 的下一个消息序号。
     *
     * @param runId Run ID
     * @return 下一个序号（从 1 开始）
     */
    public int getNextSeq(String runId) {
        Integer nextSeq = messageMapper.getNextSeq(runId);
        return nextSeq != null ? nextSeq : 1;
    }

    /**
     * 查询指定 run 的消息总数（排除 initial 消息）。
     *
     * @param runId Run ID
     * @return 消息数量
     */
    public int countMessagesExcludingInitial(String runId) {
        return messageMapper.countByRunIdExcludeInitial(runId);
    }

    /**
     * 分页查询指定 run 的消息（排除 initial 消息）。
     *
     * @param runId  Run ID
     * @param limit  每页数量
     * @param offset 偏移量
     * @return 消息列表
     */
    public List<AgentRunMessage> listMessagesWithPaginationExcludingInitial(String runId, int limit, int offset) {
        return messageMapper.listByRunIdWithPaginationExcludeInitial(runId, limit, offset);
    }

    /**
     * 构建元数据 JSON。
     *
     * @param model      模型名
     * @param endpoint   端点名
     * @param tokens     Token 数
     * @param durationMs 耗时（毫秒）
     * @return 元数据 JSON 字符串
     */
    public String buildMetaJson(String model, String endpoint, Integer tokens, Long durationMs) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        if (model != null) {
            sb.append("\"model\":\"").append(escapeJson(model)).append("\"");
            first = false;
        }
        if (endpoint != null) {
            if (!first) sb.append(",");
            sb.append("\"endpoint\":\"").append(escapeJson(endpoint)).append("\"");
            first = false;
        }
        if (tokens != null) {
            if (!first) sb.append(",");
            sb.append("\"tokens\":").append(tokens);
            first = false;
        }
        if (durationMs != null) {
            if (!first) sb.append(",");
            sb.append("\"duration_ms\":").append(durationMs);
        }
        sb.append("}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
