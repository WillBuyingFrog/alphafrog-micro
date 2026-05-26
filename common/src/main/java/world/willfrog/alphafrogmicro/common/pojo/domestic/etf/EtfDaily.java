package world.willfrog.alphafrogmicro.common.pojo.domestic.etf;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import world.willfrog.alphafrogmicro.common.pojo.domestic.quote.Quote;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "alphafrog_domestic_etf_daily",
        uniqueConstraints = @UniqueConstraint(columnNames = {"ts_code", "trade_date"}))
public class EtfDaily extends Quote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long etfDailyId;
}
