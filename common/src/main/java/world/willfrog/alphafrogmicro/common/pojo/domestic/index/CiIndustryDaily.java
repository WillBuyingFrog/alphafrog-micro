package world.willfrog.alphafrogmicro.common.pojo.domestic.index;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "alphafrog_index_ci_daily",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"ts_code", "trade_date"})
        })
public class CiIndustryDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "ts_code", nullable = false)
    String tsCode;

    @Column(name = "trade_date", nullable = false)
    Long tradeDate;

    @Column(name = "name")
    String name;

    @Column(name = "open")
    Double open;

    @Column(name = "low")
    Double low;

    @Column(name = "high")
    Double high;

    @Column(name = "close")
    Double close;

    @Column(name = "pre_close")
    Double preClose;

    @Column(name = "change_val")
    Double changeVal;

    @Column(name = "pct_change")
    Double pctChange;

    @Column(name = "vol")
    Double vol;

    @Column(name = "amount")
    Double amount;

    @Column(name = "extended", columnDefinition = "JSONB")
    String extended;
}
