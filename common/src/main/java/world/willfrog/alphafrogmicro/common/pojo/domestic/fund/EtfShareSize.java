package world.willfrog.alphafrogmicro.common.pojo.domestic.fund;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "alphafrog_fund_etf_share_size",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"ts_code", "trade_date"})
        })
public class EtfShareSize {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "trade_date", nullable = false)
    Long tradeDate;

    @Column(name = "ts_code", nullable = false)
    String tsCode;

    @Column(name = "etf_name")
    String etfName;

    @Column(name = "total_share")
    Double totalShare;

    @Column(name = "total_size")
    Double totalSize;

    @Column(name = "nav")
    Double nav;

    @Column(name = "close")
    Double close;

    @Column(name = "exchange")
    String exchange;

    @Column(name = "extended", columnDefinition = "JSONB")
    String extended;
}
