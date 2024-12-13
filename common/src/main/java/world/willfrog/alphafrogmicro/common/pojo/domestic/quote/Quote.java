package world.willfrog.alphafrogmicro.common.pojo.domestic.quote;

import jakarta.persistence.Column;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Quote {
    @Column(name = "ts_code")
    String tsCode;

    @Column(name = "trade_date")
    Long tradeDate;

    @Column(name = "close")
    Double close;

    @Column(name = "open")
    Double open;

    @Column(name = "high")
    Double high;

    @Column(name = "low")
    Double low;

    @Column(name = "pre_close")
    Double preClose;

    @Column(name = "change")
    Double change;

    @Column(name = "pct_chg")
    Double pctChg;

    @Column(name = "vol")
    Double vol;

    @Column(name = "amount")
    Double amount;


    @Override
    public String toString() {
        return tsCode + ","
                + tradeDate + ","
                + close + ","
                + open + ","
                + high + ","
                + low + ","
                + preClose + ","
                + change + ","
                + pctChg + ","
                + String.format("%.0f", vol) + ","
                + String.format("%.0f", amount);
    }

}
