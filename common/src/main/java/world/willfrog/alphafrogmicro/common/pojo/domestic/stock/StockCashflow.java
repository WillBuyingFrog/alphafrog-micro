package world.willfrog.alphafrogmicro.common.pojo.domestic.stock;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "alphafrog_stock_cashflow",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"ts_code", "end_date", "report_type"})
        })
public class StockCashflow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "ts_code", nullable = false)
    String tsCode;

    @Column(name = "ann_date")
    Long annDate;

    @Column(name = "f_ann_date")
    Long fAnnDate;

    @Column(name = "end_date", nullable = false)
    Long endDate;

    @Column(name = "comp_type")
    String compType;

    @Column(name = "report_type")
    String reportType;

    @Column(name = "end_type")
    String endType;

    // 经营活动
    @Column(name = "c_fr_sale_sg")
    Double cFrSaleSg;

    @Column(name = "n_cashflow_act")
    Double nCashflowAct;

    // 投资活动
    @Column(name = "n_cashflow_inv_act")
    Double nCashflowInvAct;

    // 筹资活动
    @Column(name = "n_cash_flows_fnc_act")
    Double nCashFlowsFncAct;

    // 汇总
    @Column(name = "free_cashflow")
    Double freeCashflow;

    @Column(name = "c_cash_equ_end_period")
    Double cCashEquEndPeriod;

    @Column(name = "n_incr_cash_cash_equ")
    Double nIncrCashCashEqu;

    @Column(name = "update_flag")
    String updateFlag;

    @Column(name = "extended", columnDefinition = "JSONB")
    String extended;
}
