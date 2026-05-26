package world.willfrog.alphafrogmicro.common.pojo.domestic.stock;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "alphafrog_stock_share_float",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"ts_code", "float_date", "holder_name", "share_type"})
        })
public class StockShareFloat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "ts_code", nullable = false)
    String tsCode;

    @Column(name = "ann_date")
    Long annDate;

    @Column(name = "float_date", nullable = false)
    Long floatDate;

    @Column(name = "float_share")
    Double floatShare;

    @Column(name = "float_ratio")
    Double floatRatio;

    @Column(name = "holder_name", nullable = false)
    String holderName;

    @Column(name = "share_type", nullable = false)
    String shareType;
}
