package world.willfrog.alphafrogmicro.common.pojo.domestic.stock;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "alphafrog_stock_top10_holders",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"ts_code", "end_date", "holder_name"})
        })
public class StockTop10Holders {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "ts_code", nullable = false)
    String tsCode;

    @Column(name = "ann_date")
    Long annDate;

    @Column(name = "end_date", nullable = false)
    Long endDate;

    @Column(name = "holder_name", nullable = false)
    String holderName;

    @Column(name = "hold_amount")
    Double holdAmount;

    @Column(name = "hold_ratio")
    Double holdRatio;

    @Column(name = "hold_float_ratio")
    Double holdFloatRatio;

    @Column(name = "hold_change")
    Double holdChange;

    @Column(name = "holder_type")
    String holderType;
}
