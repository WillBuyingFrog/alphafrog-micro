package world.willfrog.agent.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ReAct 累积式对话上下文管理器。
 *
 * <p>维护一个全局 messages 列表，在 Agent Run 的多个阶段（planning → execution → summary）
 * 之间累积 Thought / Action / Observation 历史，使 LLM 能看到完整的推理链。</p>
 *
 * <h3>缓存优势</h3>
 * <p>在 ReAct 累积模式下，System Prompt 只在第一次调用时传入，后续调用仅追加
 * User / Assistant 消息。LLM provider（如 Fireworks、OpenRouter）会自动对前缀
 * 进行 KV 缓存，从而大幅降低 TTFT 和 token 处理成本。</p>
 *
 * <h3>对话结构</h3>
 * <pre>
 * 第1次: [System] + [User goal]
 *        → [AI: 自然语言分析]
 *
 * 第2次: [System] + [User goal] + [AI 分析] + [User: 执行指令]
 *        → [AI: 执行结果/下一步推理]
 *
 * 第K次: [System] + ... + [前 K-1 次消息] + [User: 新指令]
 *        → [AI: 推理 K]
 * </pre>
 *
 * <h3>上下文压缩</h3>
 * <p>当消息数超过 {@code maxMessages} 时，自动移除最早的非系统消息，
 * 保持上下文窗口在可控范围内。</p>
 *
 * @see AgentPromptService#dynamicContextPrefix()
 */
public class ReactConversationContext {

    /** 默认最大消息数（不含 System Message）。 */
    static final int DEFAULT_MAX_MESSAGES = 50;

    /** 最少保留的非系统消息数，确保至少能容纳一组 user-assistant 交互。 */
    private static final int MIN_CONVERSATION_MESSAGES = 2;

    private final List<ChatMessage> messages;
    private final int maxMessages;

    /**
     * 创建一个空的 ReAct 上下文，使用默认最大消息数。
     */
    public ReactConversationContext() {
        this(DEFAULT_MAX_MESSAGES);
    }

    /**
     * 创建一个空的 ReAct 上下文。
     *
     * @param maxMessages 最大消息数（不含 System Message），超过时自动移除最早的消息
     */
    public ReactConversationContext(int maxMessages) {
        this.maxMessages = Math.max(maxMessages, MIN_CONVERSATION_MESSAGES);
        this.messages = new ArrayList<>();
    }

    /**
     * 设置系统 Prompt。若已有 System Message 则替换，否则插入到列表头部。
     *
     * @param systemPrompt 静态系统指令
     */
    public void setSystemMessage(String systemPrompt) {
        if (systemPrompt == null || systemPrompt.isBlank()) {
            return;
        }
        if (!messages.isEmpty() && messages.get(0) instanceof SystemMessage) {
            messages.set(0, new SystemMessage(systemPrompt));
        } else {
            messages.add(0, new SystemMessage(systemPrompt));
        }
    }

    /**
     * 追加 User Message。
     *
     * @param content 用户消息内容
     */
    public void addUserMessage(String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        messages.add(new UserMessage(content));
        trimIfNeeded();
    }

    /**
     * 追加 AI/Assistant Message。
     *
     * @param content AI 回复内容
     */
    public void addAssistantMessage(String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        messages.add(AiMessage.from(content));
        trimIfNeeded();
    }

    /**
     * 获取当前完整的 messages 列表（不可变视图），用于传入 LLM 调用。
     *
     * @return 不可变的消息列表
     */
    public List<ChatMessage> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    /**
     * 获取当前消息数（含 System Message）。
     */
    public int size() {
        return messages.size();
    }

    /**
     * 获取非系统消息数。
     */
    public int conversationSize() {
        if (messages.isEmpty()) {
            return 0;
        }
        return messages.get(0) instanceof SystemMessage ? messages.size() - 1 : messages.size();
    }

    /**
     * 清空所有消息（包括 System Message）。
     */
    public void clear() {
        messages.clear();
    }

    /**
     * 创建分支上下文（用于 DAG 并行执行时的分支隔离）。
     *
     * <p>复制当前消息列表到新实例，分支上下文独立于主上下文，
     * 修改分支不会影响原上下文。</p>
     *
     * @return 新的分支上下文（包含当前所有消息的副本）
     */
    public ReactConversationContext branch() {
        ReactConversationContext branchCtx = new ReactConversationContext(this.maxMessages);
        branchCtx.messages.addAll(this.messages);
        return branchCtx;
    }

    /**
     * 当非系统消息数超过 maxMessages 时，移除最早的非系统消息。
     * 始终保留至少一条非系统消息，避免裁剪到只剩 System Message。
     */
    private void trimIfNeeded() {
        int startIdx = (!messages.isEmpty() && messages.get(0) instanceof SystemMessage) ? 1 : 0;
        int conversationCount = messages.size() - startIdx;
        while (conversationCount > maxMessages && messages.size() > startIdx + 1) {
            messages.remove(startIdx);
            conversationCount--;
        }
    }
}
