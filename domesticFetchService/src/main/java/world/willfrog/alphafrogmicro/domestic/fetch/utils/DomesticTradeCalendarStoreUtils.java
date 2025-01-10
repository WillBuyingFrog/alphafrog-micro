package world.willfrog.alphafrogmicro.domestic.fetch.utils;

import com.alibaba.fastjson.JSONArray;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.common.dao.domestic.calendar.TradeCalendarDao;
import world.willfrog.alphafrogmicro.common.pojo.domestic.calendar.TradeCalendar;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;

import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class DomesticTradeCalendarStoreUtils {

    private final SqlSessionFactory sqlSessionFactory;


    public DomesticTradeCalendarStoreUtils(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = sqlSessionFactory;
    }


    public int storeDomesticTradeCalendarByRawTuShareOutput(JSONArray data, JSONArray fields) {
        List<TradeCalendar> tradeCalendarList = new ArrayList<>();

        try {
            for(int i = 0; i < data.size(); i++) {
                TradeCalendar tradeCalendar = new TradeCalendar();
                JSONArray item = data.getJSONArray(i);
                for (int j = 0; j < fields.size(); j++) {
                    String field = fields.getString(j);
                    switch(field) {
                        case "exchange":
                            tradeCalendar.setExchange(item.getString(j));
                            break;
                        case "cal_date":
                            long calDateTimestamp = DateConvertUtils.convertDateStrToLong(item.getString(j), "yyyyMMdd");
                            tradeCalendar.setCalDateTimestamp(calDateTimestamp);
                            break;
                        case "is_open":
                            tradeCalendar.setIsOpen(item.getInteger(j));
                            break;
                        case "pretrade_date":
                            long preTradeDateTimestamp = DateConvertUtils.convertDateStrToLong(item.getString(j), "yyyyMMdd");
                            tradeCalendar.setPreTradeDateTimestamp(preTradeDateTimestamp);
                            break;
                    }
                }
                tradeCalendarList.add(tradeCalendar);
            }
        } catch (Exception e) {
            log.error("Error occurred while parsing trade calendar data from TuShare output: {}", e.getMessage());
            return -1;
        }

        try ( SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH) ) {
            TradeCalendarDao tradeCalendarDao = sqlSession.getMapper(TradeCalendarDao.class);
            for (TradeCalendar tradeCalendar : tradeCalendarList) {
                tradeCalendarDao.insertTradeCalendar(tradeCalendar);
            }
            sqlSession.commit();
            return tradeCalendarList.size();
        } catch (Exception e) {
            log.error("Error occurred while storing trade calendar data to database: {}", e.getMessage());
            return -2;
        }
    }
}
