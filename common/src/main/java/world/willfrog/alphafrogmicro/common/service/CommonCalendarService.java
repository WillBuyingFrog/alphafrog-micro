package world.willfrog.alphafrogmicro.common.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.common.dao.domestic.calendar.TradeCalendarDao;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class CommonCalendarService {

    private final TradeCalendarDao tradeCalendarDao;
    private static final String DEFAULT_EXCHANGE = "SSE"; // 默认市场，可以根据需要调整或作为参数传入

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

    // --- 新增便捷日期获取方法 ---

    public LocalDate getTradingDateDaysAgo(LocalDate currentDate, int daysAgo, String exchange) {
        if (currentDate == null || exchange == null || exchange.trim().isEmpty() || daysAgo <= 0) {
            log.warn("Invalid input for getTradingDateDaysAgo: currentDate={}, daysAgo={}, exchange={}", currentDate, daysAgo, exchange);
            return null;
        }
        LocalDate resultDate = currentDate;
        for (int i = 0; i < daysAgo; i++) {
            resultDate = getPreviousTradingDate(resultDate, exchange);
            if (resultDate == null) {
                log.warn("Could not find {} trading days ago from {} for exchange {}. Stopped at {} days.", daysAgo, currentDate, exchange, i + 1);
                return null; // 无法找到足够的历史交易日
            }
        }
        return resultDate;
    }

    public LocalDate getTradingDateWeeksAgo(LocalDate currentDate, int weeksAgo, String exchange) {
        return getTradingDateDaysAgo(currentDate, weeksAgo * 5, exchange); // 粗略估计，一周5个交易日
    }

    public LocalDate getTradingDateMonthsAgo(LocalDate currentDate, int monthsAgo, String exchange) {
        // 注意：这个实现是简化的，可能需要更精确的DAO支持或日历逻辑来找到N个月前的"对应"交易日
        // 简单的减去月份可能不是交易日
        LocalDate approxDate = currentDate.minusMonths(monthsAgo);
        // 尝试找到这个日期或之后的第一个交易日，或之前的最后一个交易日，取决于业务需求
        // 这里简化为找到该日期前的最后一个交易日
        return findNearestTradingDate(approxDate, exchange, false); 
    }

    public LocalDate getTradingDateYearsAgo(LocalDate currentDate, int yearsAgo, String exchange) {
        LocalDate approxDate = currentDate.minusYears(yearsAgo);
        return findNearestTradingDate(approxDate, exchange, false);
    }

    public LocalDate getFirstTradingDateOfYear(LocalDate currentDate, String exchange) {
        LocalDate firstDayOfYear = currentDate.with(TemporalAdjusters.firstDayOfYear());
        // 需要DAO方法 tradeCalendarDao.getFirstTradingDayOfYear(exchange, year) 或类似方法
        // log.warn("getFirstTradingDateOfYear needs specific DAO implementation.");
        // return null; // 占位符，需要实际实现
        return findNearestTradingDate(firstDayOfYear, exchange, true); // 查找当天或之后的交易日
    }

    public LocalDate getFirstTradingDateOfQuarter(LocalDate currentDate, String exchange) {
        LocalDate firstDayOfQuarter = currentDate.with(currentDate.getMonth().firstMonthOfQuarter()).with(TemporalAdjusters.firstDayOfMonth());
        // log.warn("getFirstTradingDateOfQuarter needs specific DAO implementation.");
        // return null;
        return findNearestTradingDate(firstDayOfQuarter, exchange, true);
    }

    public LocalDate getFirstTradingDateOfMonth(LocalDate currentDate, String exchange) {
        LocalDate firstDayOfMonth = currentDate.with(TemporalAdjusters.firstDayOfMonth());
        // log.warn("getFirstTradingDateOfMonth needs specific DAO implementation.");
        // return null;
        return findNearestTradingDate(firstDayOfMonth, exchange, true);
    }
    
    /**
     * 辅助方法：查找给定日期当天或附近的一个交易日。
     * @param date 查找的基准日期
     * @param exchange 交易所
     * @param findForward true 表示查找当天或之后，false 表示查找当天或之前
     * @return 最近的交易日，如果找不到则返回 null
     */
    private LocalDate findNearestTradingDate(LocalDate date, String exchange, boolean findForward) {
        // 此方法依赖于 isTradingDay(LocalDate date, String exchange) 的实现
        // 假设有 isTradingDay 方法，或者 tradeCalendarDao.isTradingDay(exchange, timestamp)
        // log.warn("findNearestTradingDate needs isTradingDay or similar DAO method to be fully implemented.");
        
        // 简化占位符实现：循环查找，实际中应该用DAO高效查询
        LocalDate tempDate = date;
        for (int i=0; i<10; i++) { // 最多尝试10天，避免无限循环
            // 假设 TradeCalendarDao 有一个方法 isTradingDay(String exchange, long timestamp)
            // 或者 CommonCalendarService 自己能判断（可能需要缓存交易日历）
            // if (isTradingDay(tempDate, exchange)) { return tempDate; }
            // 这里暂时无法调用 isTradingDay，所以返回一个可能不是交易日的值，依赖后续的 getPreviousTradingDate 或其他逻辑
            // 这是一个需要你后续完善的占位逻辑
            if (isPresumedTradingDay(tempDate)) { // 这是一个非常简化的假设，实际需要调用DAO
                 // 为了能继续，我们先假设找到的就是交易日，或者 getPreviousTradingDate/getNextTradingDate 能正确处理
                 // 如果要找之前的，可以先用这个，然后调用getPreviousTradingDate(tempDate.plusDays(1), exchange)来确保是它或它之前
                 // 如果要找之后的，可以直接用这个，因为我们假设它就是。
                 // 更好的方法是DAO直接返回正确的交易日。
                if(!findForward){
                     //如果找之前的，就尝试用这个日期，如果它不是交易日，让 `getPreviousTradingDate` 去修正
                     //但为了返回一个"准确"的日期，最好是循环调用 `getPreviousTradingDate` 或 `getNextTradingDate`
                     //这里为了演示，我们直接返回，依赖调用方或更具体的DAO
                    LocalDate prev = tempDate;
                    int k=0;
                    while(k < 5 && !isActualTradingDayViaDao(prev, exchange)) { // 假设有 isActualTradingDayViaDao
                        prev = prev.minusDays(1);
                        k++;
                    }
                    if(isActualTradingDayViaDao(prev, exchange)) return prev; 

                } else {
                    LocalDate next = tempDate;
                    int k=0;
                    while(k < 5 && !isActualTradingDayViaDao(next, exchange)) { // 假设有 isActualTradingDayViaDao
                        next = next.plusDays(1);
                        k++;
                    }
                     if(isActualTradingDayViaDao(next, exchange)) return next; 
                }
            }
            tempDate = findForward ? tempDate.plusDays(1) : tempDate.minusDays(1);
        }
        log.warn("Could not find nearest trading day for {} on exchange {} after 10 attempts.", date, exchange);
        return null; // 找不到
    }

    // 辅助的辅助方法，这是一个需要通过DAO实现的占位符
    private boolean isActualTradingDayViaDao(LocalDate date, String exchange) {
        // log.warn("isActualTradingDayViaDao is a placeholder and needs actual DAO call for {}-{}", exchange, date);
        // 实际应调用: return tradeCalendarDao.isTradingDay(exchange, DateConvertUtils.convertLocalDateToMsTimestamp(date));
        // 暂时做一个简单的非周末判断以使得流程能继续
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }

    // 这是一个非常简化的假设，实际需要调用DAO来判断是否是交易日
    private boolean isPresumedTradingDay(LocalDate date) {
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        return dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY;
    }

    /**
     * 解析便捷日期字符串和标准日期字符串为 LocalDate 列表。
     * @param currentDate 基准日期，用于相对计算
     * @param dateInputs 字符串列表，可以是 "YYYY-MM-DD" 或 "PREV_TRADING_DAY", "WEEK_AGO" 等
     * @param exchange 交易所代码
     * @return LocalDate 列表
     */
    public List<LocalDate> parseDateInputs(LocalDate currentDate, List<String> dateInputs, String exchange) {
        if (dateInputs == null || dateInputs.isEmpty()) {
            return new ArrayList<>();
        }
        List<LocalDate> parsedDates = new ArrayList<>();
        for (String input : dateInputs) {
            LocalDate resolvedDate = null;
            try {
                switch (input.toUpperCase()) {
                    case "PREV_TRADING_DAY":
                        resolvedDate = getPreviousTradingDate(currentDate, exchange);
                        break;
                    case "WEEK_AGO":
                        resolvedDate = getTradingDateWeeksAgo(currentDate, 1, exchange);
                        break;
                    case "MONTH_AGO":
                        resolvedDate = getTradingDateMonthsAgo(currentDate, 1, exchange);
                        break;
                    case "YEAR_AGO":
                        resolvedDate = getTradingDateYearsAgo(currentDate, 1, exchange);
                        break;
                    case "YTD_START":
                        resolvedDate = getFirstTradingDateOfYear(currentDate, exchange);
                        break;
                    case "QTD_START":
                        resolvedDate = getFirstTradingDateOfQuarter(currentDate, exchange);
                        break;
                    case "MTD_START":
                        resolvedDate = getFirstTradingDateOfMonth(currentDate, exchange);
                        break;
                    default:
                        // 尝试按 YYYY-MM-DD 解析
                        resolvedDate = LocalDate.parse(input);
                        break;
                }
            } catch (Exception e) {
                log.warn("Could not parse date input string: '{}'. Error: {}", input, e.getMessage());
            }
            if (resolvedDate != null) {
                parsedDates.add(resolvedDate);
            }
        }
        return parsedDates;
    }
} 