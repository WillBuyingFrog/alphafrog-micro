package world.willfrog.alphafrogmicro.frontend.model.agent;

/**
 * 发送消息请求。
 *
 * @param content          消息内容（必填）
 * @param contextOverride  上下文覆盖（仅 admin + debugMode 可用）
 * @param stream           是否流式响应（预留）
 */
public record AgentMessageSendRequest(
        String content,
        String contextOverride,
        Boolean stream
) {
}
