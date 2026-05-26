package world.willfrog.alphafrogmicro.common.pojo.domestic.stock;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "alphafrog_stock_moneyflow",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"ts_code", "trade_date"})
        })
public class StockMoneyflow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "ts_code", nullable = false)
    String tsCode;

    @Column(name = "trade_date", nullable = false)
    Long tradeDate;

    // 小单
    @Column(name = "buy_sm_vol")
    Long buySmVol;

    @Column(name = "buy_sm_amount")
    Double buySmAmount;

    @Column(name = "sell_sm_vol")
    Long sellSmVol;

    @Column(name = "sell_sm_amount")
    Double sellSmAmount;

    // 中单
    @Column(name = "buy_md_vol")
    Long buyMdVol;

    @Column(name = "buy_md_amount")
    Double buyMdAmount;

    @Column(name = "sell_md_vol")
    Long sellMdVol;

    @Column(name = "sell_md_amount")
    Double sellMdAmount;

    // 大单
    @Column(name = "buy_lg_vol")
    Long buyLgVol;

    @Column(name = "buy_lg_amount")
    Double buyLgAmount;

    @Column(name = "sell_lg_vol")
    Long sellLgVol;

    @Column(name = "sell_lg_amount")
    Double sellLgAmount;

    // 特大单
    @Column(name = "buy_elg_vol")
    Long buyElgVol;

    @Column(name = "buy_elg_amount")
    Double buyElgAmount;

    @Column(name = "sell_elg_vol")
    Long sellElgVol;

    @Column(name = "sell_elg_amount")
    Double sellElgAmount;

    // 净流入
    @Column(name = "net_mf_vol")
    Long netMfVol;

    @Column(name = "net_mf_amount")
    Double netMfAmount;
}
