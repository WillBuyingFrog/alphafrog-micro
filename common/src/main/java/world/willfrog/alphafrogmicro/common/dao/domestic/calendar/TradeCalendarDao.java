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

    @Select("SELECT cal_date_timestamp FROM alphafrog_trade_calendar " +
            "WHERE exchange = #{exchange} AND is_open = 1 " +
            "AND cal_date_timestamp BETWEEN #{startDateTimestamp} AND #{endDateTimestamp} " +
            "ORDER BY cal_date_timestamp")
    List<Long> getTradingDatesByRange(String exchange, long startDateTimestamp, long endDateTimestamp);

    @Select("SELECT COUNT(1) FROM alphafrog_trade_calendar " +
            "WHERE exchange = #{exchange} AND is_open = 1 " +
            "AND cal_date_timestamp BETWEEN #{startDateTimestamp} AND #{endDateTimestamp}")
    int countTradingDaysByRange(String exchange, long startDateTimestamp, long endDateTimestamp);

    /**
     * Finds the latest actual trading day timestamp (YYYYMMDD long format)
     * that is strictly before the given currentCalDateLongYYYYMMDD.
     *
     * @param exchange The stock exchange identifier (e.g., "SSE").
     * @param currentCalDateTimestamp The current date as a long timestamp,
     *                                   the search will be for trading days before this date.
     * @return The timestamp of the previous trading day in YYYYMMDD long format, or null if not found.
     */
    @Select("SELECT cal_date_timestamp FROM alphafrog_trade_calendar " +
            "WHERE exchange = #{exchange} AND cal_date_timestamp < #{currentCalDateTimestamp} AND is_open = 1 " +
            "ORDER BY cal_date_timestamp DESC LIMIT 1")
    Long getActualPreviousTradingDayTimestamp(String exchange, long currentCalDateTimestamp);

}
