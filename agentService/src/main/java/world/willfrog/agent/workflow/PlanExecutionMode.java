package world.willfrog.agent.workflow;

/**
 * 计划执行模式。
 */
public enum PlanExecutionMode {
    /** 线性顺序执行 */
    LINEAR,
    /** DAG 并行执行 */
    DAG,
    /** 自动选择（根据 Plan 特征） */
    AUTO
}
