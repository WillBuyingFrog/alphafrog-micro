package world.willfrog.alphafrogmicro.common.pojo.domestic.stock;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import world.willfrog.alphafrogmicro.common.pojo.domestic.quote.Quote;


@Entity
@Table(name = "alphafrog_stock_daily",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"ts_code", "trade_date"})
        }
)
@Getter
@Setter
@NoArgsConstructor
public class StockDaily extends Quote {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long stockDailyId;

    @Override
    public String toString() {
        return "StockDaily{" +
                "stockDailyId=" + stockDailyId +
                ", tsCode='" + tsCode + '\'' +
                ", tradeDate=" + tradeDate +
                ", close=" + close +
                ", open=" + open +
                ", high=" + high +
                ", low=" + low +
                ", preClose=" + preClose +
                ", change=" + change +
                ", pctChg=" + pctChg +
                ", vol=" + vol +
                ", amount=" + amount +
                '}';
    }

}
