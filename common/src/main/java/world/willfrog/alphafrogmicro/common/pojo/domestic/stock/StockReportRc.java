package world.willfrog.alphafrogmicro.common.pojo.domestic.stock;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "alphafrog_stock_report_rc",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"ts_code", "report_date", "org_name", "quarter"})
        })
public class StockReportRc {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "ts_code", nullable = false)
    String tsCode;

    @Column(name = "name")
    String name;

    @Column(name = "report_date", nullable = false)
    Long reportDate;

    @Column(name = "report_title")
    String reportTitle;

    @Column(name = "report_type")
    String reportType;

    @Column(name = "classify")
    String classify;

    @Column(name = "org_name", nullable = false)
    String orgName;

    @Column(name = "author_name")
    String authorName;

    @Column(name = "quarter", nullable = false)
    String quarter;

    @Column(name = "op_rt")
    Double opRt;

    @Column(name = "op_pr")
    Double opPr;

    @Column(name = "tp")
    Double tp;

    @Column(name = "np")
    Double np;

    @Column(name = "eps")
    Double eps;

    @Column(name = "pe")
    Double pe;

    @Column(name = "rd")
    Double rd;

    @Column(name = "roe")
    Double roe;

    @Column(name = "ev_ebitda")
    Double evEbitda;

    @Column(name = "rating")
    String rating;

    @Column(name = "max_price")
    Double maxPrice;

    @Column(name = "min_price")
    Double minPrice;
}
