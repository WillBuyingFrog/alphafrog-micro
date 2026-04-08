package world.willfrog.alphafrogmicro.common.pojo.domestic.stock;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "alphafrog_stock_express",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"ts_code", "ann_date", "end_date"})
        })
public class StockExpress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "ts_code", nullable = false)
    String tsCode;

    @Column(name = "ann_date")
    Long annDate;

    @Column(name = "end_date")
    Long endDate;

    @Column(name = "revenue")
    Double revenue;

    @Column(name = "operate_profit")
    Double operateProfit;

    @Column(name = "total_profit")
    Double totalProfit;

    @Column(name = "n_income")
    Double nIncome;

    @Column(name = "total_assets")
    Double totalAssets;

    @Column(name = "total_hldr_eqy_exc_min_int")
    Double totalHldrEqyExcMinInt;

    @Column(name = "diluted_eps")
    Double dilutedEps;

    @Column(name = "diluted_roe")
    Double dilutedRoe;

    @Column(name = "yoy_net_profit")
    Double yoyNetProfit;

    @Column(name = "bps")
    Double bps;

    @Column(name = "yoy_sales")
    Double yoySales;

    @Column(name = "yoy_op")
    Double yoyOp;

    @Column(name = "yoy_tp")
    Double yoyTp;

    @Column(name = "yoy_dedu_np")
    Double yoyDeduNp;

    @Column(name = "yoy_eps")
    Double yoyEps;

    @Column(name = "yoy_roe")
    Double yoyRoe;

    @Column(name = "perf_summary", columnDefinition = "TEXT")
    String perfSummary;

    @Column(name = "is_audit")
    Integer isAudit;

    @Column(name = "remark", columnDefinition = "TEXT")
    String remark;
}
