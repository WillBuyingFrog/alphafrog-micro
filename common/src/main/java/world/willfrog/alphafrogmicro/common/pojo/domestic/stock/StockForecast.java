package world.willfrog.alphafrogmicro.common.pojo.domestic.stock;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "alphafrog_stock_forecast",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"ts_code", "ann_date", "end_date"})
        })
public class StockForecast {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "ts_code", nullable = false)
    String tsCode;

    @Column(name = "ann_date", nullable = false)
    Long annDate;

    @Column(name = "end_date")
    Long endDate;

    @Column(name = "type")
    String type;

    @Column(name = "p_change_min")
    Double pChangeMin;

    @Column(name = "p_change_max")
    Double pChangeMax;

    @Column(name = "net_profit_min")
    Double netProfitMin;

    @Column(name = "net_profit_max")
    Double netProfitMax;

    @Column(name = "last_parent_net")
    Double lastParentNet;

    @Column(name = "first_ann_date")
    Long firstAnnDate;

    @Column(name = "summary", columnDefinition = "TEXT")
    String summary;

    @Column(name = "change_reason", columnDefinition = "TEXT")
    String changeReason;
}
