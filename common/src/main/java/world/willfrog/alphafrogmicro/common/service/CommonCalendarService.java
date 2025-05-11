package world.willfrog.alphafrogmicro.common.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.common.dao.domestic.calendar.TradeCalendarDao;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;

import java.time.LocalDate;

@Service
@Slf4j
public class CommonCalendarService {

    private final TradeCalendarDao tradeCalendarDao;

    public CommonCalendarService(TradeCalendarDao tradeCalendarDao) {
        this.tradeCalendarDao = tradeCalendarDao;
    }

    /**
     * Gets the actual trading date strictly before the given date for a specific exchange.
     *
     * @param currentDate The reference date.
     * @param exchange    The stock exchange identifier (e.g., "SSE").
     * @return The previous trading date as a LocalDate, or null if not found or input is invalid.
     */
    public LocalDate getPreviousTradingDate(LocalDate currentDate, String exchange) {
        if (currentDate == null || exchange == null || exchange.trim().isEmpty()) {
            log.warn("Invalid input for getPreviousTradingDate: currentDate={}, exchange={}", currentDate, exchange);
            return null;
        }

        try {
            // Convert LocalDate to Asia/Shanghai 00:00:00 millisecond timestamp
            long currentCalDateTimestamp = DateConvertUtils.convertLocalDateToMsTimestamp(currentDate);
            
            // The DAO method now expects a millisecond timestamp and returns a millisecond timestamp
            Long prevTradingDayTimestamp = tradeCalendarDao.getActualPreviousTradingDayTimestamp(exchange, currentCalDateTimestamp);

            if (prevTradingDayTimestamp != null && prevTradingDayTimestamp > 0) {
                // Convert the returned millisecond timestamp back to LocalDate
                return DateConvertUtils.convertTimestampToLocalDate(prevTradingDayTimestamp);
            }
            log.info("No previous trading date found for currentDate: {}, exchange: {}. (Query timestamp: {})", 
                     currentDate, exchange, currentCalDateTimestamp);
            return null;
        } catch (IllegalArgumentException e) {
            log.error("Error converting dates for getPreviousTradingDate (currentDate: {}, exchange: {}): {}", 
                      currentDate, exchange, e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Unexpected error in getPreviousTradingDate (currentDate: {}, exchange: {}): {}", 
                      currentDate, exchange, e.getMessage(), e);
            return null;
        }
    }
} 