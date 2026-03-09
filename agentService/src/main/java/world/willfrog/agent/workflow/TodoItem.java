package world.willfrog.agent.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 简化的 TodoItem - ReAct 模式下的任务项。
 *
 * <p>设计原则：Todo 只包含简短的意图描述，不包含具体的工具参数。
 * 执行时由 LLM 根据上下文自主决策如何完成该 Todo。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TodoItem {

    /** 唯一标识，如 todo_1, todo_2 */
    private String id;

    /** 执行顺序 */
    private int sequence;

    /** 简短的任务描述（1-3句话），如"查询贵州茅台的股票代码" */
    private String description;

    /** 任务状态 */
    private TodoStatus status;

    /** 执行结果摘要 */
    private String resultSummary;

    /** 完整输出（JSON 格式） */
    private String output;

    /** 创建时间 */
    private Instant createdAt;

    /** 完成时间 */
    private Instant completedAt;

    // ── DAG 并行执行字段（可选）──

    /** 依赖的 todoId 列表（空列表表示可立即执行） */
    @Builder.Default
    private List<String> dependsOn = new ArrayList<>();

    /** 分组键（同组任务可并行） */
    private String groupKey;

    /** 是否可并行化 */
    private boolean parallelizable;
}
