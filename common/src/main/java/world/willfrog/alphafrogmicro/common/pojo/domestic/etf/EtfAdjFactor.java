package world.willfrog.alphafrogmicro.common.pojo.domestic.etf;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "alphafrog_domestic_etf_adj_factor",
        uniqueConstraints = @UniqueConstraint(columnNames = {"ts_code", "trade_date"}))
public class EtfAdjFactor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ts_code", nullable = false)
    private String tsCode;

    @Column(name = "trade_date", nullable = false)
    private Long tradeDate;

    @Column(name = "adj_factor", nullable = false)
    private Double adjFactor;
}
