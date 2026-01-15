package world.willfrog.alphafrogmicro.common.pojo.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// 如果项目使用 Lombok，可以添加 @Data, @Builder, @NoArgsConstructor, @AllArgsConstructor 等注解
// 否则需要手动添加构造函数、getter 和 setter


@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class PortfolioPerformanceRelativeToDateDto {
    private LocalDate referenceDate; // 比较基准的历史日期
    private BigDecimal marketValueAtReferenceDate; // 投资组合在该历史日期的总市值
    private BigDecimal returnAmountSinceReferenceDate; // 从该历史日期到 currentDate 的回报金额
    private BigDecimal returnRateSinceReferenceDate;   // 从该历史日期到 currentDate 的回报率

    @Override
    public String toString() {
        return "PortfolioPerformanceRelativeToDateDto{" +
                "referenceDate=" + referenceDate +
                ", marketValueAtReferenceDate=" + marketValueAtReferenceDate +
                ", returnAmountSinceReferenceDate=" + returnAmountSinceReferenceDate +
                ", returnRateSinceReferenceDate=" + returnRateSinceReferenceDate +
                '}';
    }
} 