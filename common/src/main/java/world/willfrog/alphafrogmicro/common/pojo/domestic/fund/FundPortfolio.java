package world.willfrog.alphafrogmicro.common.pojo.domestic.fund;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "alphafrog_fund_portfolio",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"ts_code", "symbol", "ann_date"})
        })
public class FundPortfolio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long fundPortfolioId;

    // 基金代码
    @Column(name = "ts_code", nullable = false)
    String tsCode;

    // 持仓公告日期
    @Column(name = "ann_date", nullable = false)
    Long annDate;

    // 持仓截止日期
    @Column(name = "end_date")
    Long endDate;

    // 持仓股票代码
    @Column(name = "symbol", nullable = false)
    String symbol;

    // 持有股票市值
    @Column(name = "mkv")
    Double mkv;

    // 持有股票数量
    @Column(name = "amount")
    Double amount;

    // 占股票市值比
    @Column(name = "stk_mkv_ratio")
    Double stkMkvRatio;

    // 占流通股本比例
    @Column(name = "stk_float_ratio")
    Double stkFloatRatio;

}
