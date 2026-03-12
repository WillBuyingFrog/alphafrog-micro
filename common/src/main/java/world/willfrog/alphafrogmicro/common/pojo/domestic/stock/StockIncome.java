package world.willfrog.alphafrogmicro.common.pojo.domestic.stock;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "alphafrog_stock_income",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"ts_code", "end_date", "report_type"})
        })
public class StockIncome {

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

    @Column(name = "report_type")
    String reportType;

    @Column(name = "comp_type")
    String compType;

    @Column(name = "end_type")
    String endType;

    @Column(name = "basic_eps")
    Double basicEps;

    @Column(name = "diluted_eps")
    Double dilutedEps;

    @Column(name = "total_revenue")
    Double totalRevenue;

    @Column(name = "revenue")
    Double revenue;

    @Column(name = "total_cogs")
    Double totalCogs;

    @Column(name = "operate_profit")
    Double operateProfit;

    @Column(name = "total_profit")
    Double totalProfit;

    @Column(name = "n_income")
    Double nIncome;

    @Column(name = "n_income_attr_p")
    Double nIncomeAttrP;

    @Column(name = "ebit")
    Double ebit;

    @Column(name = "ebitda")
    Double ebitda;

    @Column(name = "rd_exp")
    Double rdExp;

    @Column(name = "update_flag")
    String updateFlag;

    @Column(name = "extended", columnDefinition = "JSONB")
    String extended;
}
