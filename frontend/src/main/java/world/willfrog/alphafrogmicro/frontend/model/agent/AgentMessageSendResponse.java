package world.willfrog.alphafrogmicro.frontend.model.agent;

/**
 * 发送消息响应。
 *
 * @param messageId    消息 ID
 * @param seq          分配的序号
 * @param status       状态：accepted | rejected
 * @param runStatus    处理后的 run 状态
 * @param rejectReason 拒绝原因（如被拒绝）
 */
public record AgentMessageSendResponse(
        Long messageId,
        Integer seq,
        String status,
        String runStatus,
        String rejectReason
) {
}
