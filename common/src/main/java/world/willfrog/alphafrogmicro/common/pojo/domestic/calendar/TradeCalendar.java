package world.willfrog.alphafrogmicro.common.pojo.domestic.calendar;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@NoArgsConstructor
@Getter
@Setter
@Table(name = "alphafrog_trade_calendar",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"exchange", "cal_date_timestamp"})
        })
public class TradeCalendar {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long tradeCalendarId;

    // 交易所
    @Column(name = "exchange")
    String exchange;

    // 交易日期对应的毫秒时间戳
    @Column(name = "cal_date_timestamp")
    Long calDateTimestamp;

    // 交易状态
    @Column(name = "is_open")
    int isOpen;

    // 上一个交易日对应的毫秒时间戳
    @Column(name = "pre_trade_date_timestamp")
    Long preTradeDateTimestamp;

}
