package world.willfrog.agent.parity;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Golden parity 测试用例定义。
 *
 * <p>每个 case 描述一个期望的 Run 语义行为，harness 用 fixture 构造输入、
 * 执行 legacy 工作流、然后校验输出事件/状态/artifact 是否与期望一致。</p>
 */
@Data
@Builder
public class ParityGoldenCase {

    /** 用例唯一标识 */
    private String caseId;

    /** 人类可读描述 */
    private String description;

    /** 用户目标（goal） */
    private String goal;

    /** 模型配置名（对应 agent-llm.json 中的配置） */
    private String modelConfigName;

    /** 期望最终 Run 状态 */
    private String expectedStatus;

    /** 期望必须出现的关键事件类型列表 */
    @Builder.Default
    private List<String> expectedEvents = List.of();

    /** 期望不能出现的事件类型列表 */
    @Builder.Default
    private List<String> forbiddenEvents = List.of();

    /** 期望失败类别（如果预期失败） */
    private String expectedFailureCategory;

    /** 期望 final answer 非空 */
    @Builder.Default
    private boolean expectNonEmptyFinalAnswer = true;

    /** 期望 dataset 行为：是否有 dataset handoff */
    @Builder.Default
    private boolean expectDatasetHandoff = false;

    /** 期望 budget 检查：是否应触发 budget kill */
    @Builder.Default
    private boolean expectBudgetKill = false;

    /** 自定义校验器：case 专属断言 */
    @Builder.Default
    private java.util.function.Consumer<ParityRunResult> customValidator = r -> {};
}
