package world.willfrog.alphafrogmicro.common.dao.domestic.calendar;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import world.willfrog.alphafrogmicro.common.pojo.domestic.calendar.TradeCalendar;

import java.util.List;

@Mapper
public interface TradeCalendarDao {

    @Insert("INSERT INTO alphafrog_trade_calendar (exchange, cal_date_timestamp, is_open, pre_trade_date_timestamp) " +
            "VALUES (#{exchange}, #{calDateTimestamp}, #{isOpen}, #{preTradeDateTimestamp})" +
            "ON CONFLICT DO NOTHING")
    int insertTradeCalendar(TradeCalendar tradeCalendar);

    @Select("SELECT * FROM alphafrog_trade_calendar WHERE " +
            "cal_date_timestamp BETWEEN #{startDateTimestamp} AND #{endDateTimestamp}")
    List<TradeCalendar> getTradeCalendarByDateRange(long startDateTimestamp, long endDateTimestamp);

}
