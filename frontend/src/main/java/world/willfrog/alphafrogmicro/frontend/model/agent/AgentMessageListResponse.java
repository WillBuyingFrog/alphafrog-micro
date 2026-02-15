package world.willfrog.alphafrogmicro.frontend.model.agent;

import java.util.List;

/**
 * 消息列表响应。
 *
 * @param items   消息列表
 * @param total   总数
 * @param hasMore 是否有更多
 */
public record AgentMessageListResponse(
        List<AgentMessageItemResponse> items,
        Integer total,
        Boolean hasMore
) {
}
