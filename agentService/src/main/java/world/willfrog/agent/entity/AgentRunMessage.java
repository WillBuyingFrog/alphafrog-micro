package world.willfrog.agent.entity;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * Agent Run 多轮对话消息实体。
 * <p>
 * 对应表：alphafrog_agent_run_message
 * <p>
 * 职责：
 * 1. 存储用户与助手的多轮对话历史
 * 2. 支持消息序号（seq）用于排序
 * 3. 支持消息类型区分初始问题/追问/摘要
 * 4. 支持元数据存储（token 数、模型信息等）
 *
 * @author kimi
 */
@Data
public class AgentRunMessage {

    /** 主键 ID */
    private Long id;

    /** 关联的 Run ID */
    private String runId;

    /** 消息序号，同一 run 内从 1 开始递增 */
    private Integer seq;

    /** 角色：user/assistant/system */
    private String role;

    /** 消息内容 */
    private String content;

    /** 元数据 JSON（token 数、模型名、耗时等） */
    private String metaJson;

    /** 消息类型：initial/follow_up/summary */
    private String msgType;

    /** 创建时间 */
    private OffsetDateTime createdAt;

    // ----- 业务常量 -----

    /** 角色：用户 */
    public static final String ROLE_USER = "user";

    /** 角色：助手 */
    public static final String ROLE_ASSISTANT = "assistant";

    /** 角色：系统 */
    public static final String ROLE_SYSTEM = "system";

    /** 消息类型：初始问题 */
    public static final String MSG_TYPE_INITIAL = "initial";

    /** 消息类型：追问 */
    public static final String MSG_TYPE_FOLLOW_UP = "follow_up";

    /** 消息类型：上下文摘要 */
    public static final String MSG_TYPE_SUMMARY = "summary";
}
