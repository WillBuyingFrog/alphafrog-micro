package world.willfrog.alphafrogmicro.frontend.model.agent;

/**
 * 消息列表项。
 *
 * @param id         消息 ID
 * @param seq        消息序号
 * @param role       角色：user/assistant/system
 * @param content    消息内容
 * @param msgType    消息类型：initial/follow_up/summary
 * @param metaJson   元数据 JSON
 * @param createdAt  创建时间
 */
public record AgentMessageItemResponse(
        Long id,
        Integer seq,
        String role,
        String content,
        String msgType,
        String metaJson,
        String createdAt
) {
}
