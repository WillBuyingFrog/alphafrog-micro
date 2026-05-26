package world.willfrog.agent.workflow;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 已完成的 Todo 信息，用于在 ReAct 上下文中传递。
 * <p>
 * 包含完整的消息历史（CoT），使后续 Todo 可以看到之前任务的完整思考过程。
 * 
 * @author kimi
 * @since 2026-03-26
 */
@Data
@Builder
public class CompletedTodoInfo {
    private String todoId;
    private String description;
    private String output;
    private String summary;
    
    /**
     * 完整的消息历史（CoT），保存该 Todo 执行过程中的所有对话消息。
     * <p>
     * 包含：SystemMessage, UserMessage, AiMessage, ToolExecutionResultMessage
     * 用于在后续 Todo 中重建完整的上下文，保持多轮对话的连贯性。
     */
    @Builder.Default
    private List<ChatMessageSnapshot> messageHistory = new ArrayList<>();
    
    /**
     * 消息快照，用于序列化保存消息历史。
     */
    @Data
    @Builder
    public static class ChatMessageSnapshot {
        /**
         * 消息角色：system, user, assistant, tool
         */
        private String role;
        
        /**
         * 消息内容
         */
        private String content;
        
        /**
         * 工具名（role=tool 时）
         */
        private String toolName;
        
        /**
         * 工具调用ID（role=tool 时）
         */
        private String toolCallId;
        
        /**
         * 工具调用请求（role=assistant 且包含工具调用时）
         */
        private List<ToolCallSnapshot> toolCalls;
        
        /**
         * 消息时间戳
         */
        @Builder.Default
        private long timestamp = System.currentTimeMillis();
    }
    
    /**
     * 工具调用快照
     */
    @Data
    @Builder
    public static class ToolCallSnapshot {
        private String id;
        private String name;
        private String arguments;
    }
}
