package world.willfrog.alphafrogmicro.common.pojo.domestic.fund;

import jakarta.persistence.*;
import lombok.*;

import java.util.Objects;


@Entity
@Getter
@Setter
@ToString
@NoArgsConstructor
@Table(name = "alphafrog_fund_info",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"ts_code"})
        })
public class FundInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long fundPortfolioId;

    // 基金代码
    @Column(name = "ts_code", nullable = false)
    String tsCode;

    // 简称
    @Column(name = "name")
    String name;

    // 管理人
    @Column(name = "management")
    String management;

    // 托管人
    @Column(name = "custodian")
    String custodian;

    // 投资类型
    @Column(name = "fund_type")
    String fundType;

    // 成立日期
    @Column(name = "found_date")
    Long foundDate;

    // 到期日期
    @Column(name = "due_date")
    Long dueDate;

    // 上市时间
    @Column(name = "list_date")
    Long listDate;

    // 发行日期
    @Column(name = "issue_date")
    Long issueDate;

    // 退市日期
    @Column(name = "delist_date")
    Long delistDate;

    // 发行份额（亿份）
    @Column(name = "issue_amount")
    Double issueAmount;

    // 管理费
    @Column(name = "m_fee")
    Double mFee;

    // 托管费
    @Column(name = "c_fee")
    Double cFee;

    // 存续期
    @Column(name = "duration_year")
    Double durationYear;

    // 面值
    @Column(name = "p_value")
    Double pValue;

    // 起点金额（万元）
    @Column(name = "min_amount")
    Double minAmount;

    // 预期收益率
    @Column(name = "exp_return")
    Double expReturn;

    // 业绩比较基准
    @Column(name = "benchmark", length = 500)
    String benchmark;

    // 存续状态或摘牌
    @Column(name = "status", length = 2)
    String status;

    // 投资风格
    @Column(name = "invest_type")
    String investType;

    // 基金类型
    @Column(name = "type", length = 10)
    String type;

    // 受托人
    @Column(name = "trustee", length = 20)
    String trustee;

    // 日常申购起始日
    @Column(name = "purc_startdate")
    Long purcStartDate;

    // 日常赎回起始日
    @Column(name = "redm_startdate")
    Long redmStartDate;

    // E场内O场外
    @Column(name = "market", length = 2)
    String market;

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FundInfo fundInfo = (FundInfo) o;
        return getFundPortfolioId() != null && Objects.equals(getFundPortfolioId(), fundInfo.getFundPortfolioId());
    }

    @Override
    public final int hashCode() {
        return getClass().hashCode();
    }

    public String toString(){
        return "FundInfo(fundPortfolioId=" + this.getFundPortfolioId() + ", tsCode=" + this.getTsCode() + ", name=" + this.getName() + ", management=" + this.getManagement() + ", custodian=" + this.getCustodian() + ", fundType=" + this.getFundType() + ", foundDate=" + this.getFoundDate() + ", dueDate=" + this.getDueDate() + ", listDate=" + this.getListDate() + ", issueDate=" + this.getIssueDate() + ", delistDate=" + this.getDelistDate() + ", issueAmount=" + this.getIssueAmount() + ", mFee=" + this.getMFee() + ", cFee=" + this.getCFee() + ", durationYear=" + this.getDurationYear() + ", pValue=" + this.getPValue() + ", minAmount=" + this.getMinAmount() + ", expReturn=" + this.getExpReturn() + ", benchmark=" + this.getBenchmark() + ", status=" + this.getStatus() + ", investType=" + this.getInvestType() + ", type=" + this.getType() + ", trustee=" + this.getTrustee() + ", purcStartDate=" + this.getPurcStartDate() + ", redmStartDate=" + this.getRedmStartDate() + ", market=" + this.getMarket() + ")";
    }

}

