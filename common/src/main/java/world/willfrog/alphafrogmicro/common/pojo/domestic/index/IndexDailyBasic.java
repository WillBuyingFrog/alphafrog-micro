package world.willfrog.alphafrogmicro.common.pojo.domestic.index;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "alphafrog_index_daily_basic",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"ts_code", "trade_date"})
        })
public class IndexDailyBasic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "ts_code", nullable = false)
    String tsCode;

    @Column(name = "trade_date", nullable = false)
    Long tradeDate;

    @Column(name = "total_mv")
    Double totalMv;

    @Column(name = "float_mv")
    Double floatMv;

    @Column(name = "total_share")
    Double totalShare;

    @Column(name = "float_share")
    Double floatShare;

    @Column(name = "free_share")
    Double freeShare;

    @Column(name = "turnover_rate")
    Double turnoverRate;

    @Column(name = "turnover_rate_f")
    Double turnoverRateF;

    @Column(name = "pe")
    Double pe;

    @Column(name = "pe_ttm")
    Double peTtm;

    @Column(name = "pb")
    Double pb;
}
