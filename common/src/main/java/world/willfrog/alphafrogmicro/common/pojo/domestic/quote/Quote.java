package world.willfrog.alphafrogmicro.common.pojo.domestic.quote;

import jakarta.persistence.Column;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Quote {
    @Column(name = "ts_code")
    public String tsCode;

    @Column(name = "trade_date")
    public Long tradeDate;

    @Column(name = "close")
    public Double close;

    @Column(name = "open")
    public Double open;

    @Column(name = "high")
    public Double high;

    @Column(name = "low")
    public Double low;

    @Column(name = "pre_close")
    public Double preClose;

    @Column(name = "change")
    public Double change;

    @Column(name = "pct_chg")
    public Double pctChg;

    @Column(name = "vol")
    public Double vol;

    @Column(name = "amount")
    public Double amount;


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
