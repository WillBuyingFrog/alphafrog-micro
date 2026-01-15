package world.willfrog.alphafrogmicro.common.dto.compact;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 紧凑JSON响应的元数据
 * 包含数据完整性、分页、状态等信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompactMeta {
    
    /**
     * 股票/基金/指数代码
     */
    private String tsCode;
    
    /**
     * 股票代码（用于持仓查询）
     */
    private String symbol;
    
    /**
     * 搜索查询词
     */
    private String query;
    
    /**
     * 开始日期（时间戳）
     */
    private Long startDate;
    
    /**
     * 结束日期（时间戳）
     */
    private Long endDate;
    
    /**
     * 预期交易日数量
     */
    private Integer expectedTradingDays;
    
    /**
     * 实际交易日数量
     */
    private Integer actualTradingDays;
    
    /**
     * 缺失数据数量
     */
    private Integer missingCount;
    
    /**
     * 数据是否完整
     */
    private Boolean complete;
    
    /**
     * 上游数据是否存在缺口
     */
    private Boolean upstreamGap;
    
    /**
     * 是否来自缓存
     */
    private Boolean fromCache;
    
    /**
     * 当前页码（从1开始）
     */
    private Integer page;
    
    /**
     * 每页大小
     */
    private Integer pageSize;
    
    /**
     * 总记录数
     */
    private Long total;
    
    /**
     * 状态信息（错误状态等）
     */
    private String status;
    
    /**
     * 创建基础元数据的便捷方法
     */
    public static CompactMeta basic() {
        return CompactMeta.builder().build();
    }
    
    /**
     * 创建分页元数据的便捷方法
     */
    public static CompactMeta page(int page, int pageSize, long total) {
        return CompactMeta.builder()
                .page(page)
                .pageSize(pageSize)
                .total(total)
                .build();
    }
    
    /**
     * 创建数据完整性元数据的便捷方法
     */
    public static CompactMeta completeness(String tsCode, Long startDate, Long endDate,
                                         Integer expectedTradingDays, Integer actualTradingDays,
                                         Integer missingCount, Boolean complete) {
        return CompactMeta.builder()
                .tsCode(tsCode)
                .startDate(startDate)
                .endDate(endDate)
                .expectedTradingDays(expectedTradingDays)
                .actualTradingDays(actualTradingDays)
                .missingCount(missingCount)
                .complete(complete)
                .build();
    }
}