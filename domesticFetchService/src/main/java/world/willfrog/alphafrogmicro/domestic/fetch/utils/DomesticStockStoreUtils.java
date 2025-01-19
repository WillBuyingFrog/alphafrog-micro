package world.willfrog.alphafrogmicro.domestic.fetch.utils;

import com.alibaba.fastjson.JSONArray;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.common.dao.domestic.stock.StockInfoDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.stock.StockQuoteDao;
import world.willfrog.alphafrogmicro.common.pojo.domestic.stock.StockDaily;
import world.willfrog.alphafrogmicro.common.pojo.domestic.stock.StockInfo;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;

import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class DomesticStockStoreUtils {
    private final SqlSessionFactory sqlSessionFactory;

    public DomesticStockStoreUtils(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = sqlSessionFactory;
    }


    public int storeStockInfoByRawTuShareOutput(JSONArray data, JSONArray fields) {
        List<StockInfo> stockInfoList = new ArrayList<>();

        try {
            for (int i = 0; i < data.size(); i++) {
                StockInfo stockInfo = new StockInfo();
                JSONArray item = data.getJSONArray(i);
                for (int j = 0; j < fields.size(); j++) {
                    String field = fields.getString(j);
                    switch (field) {
                        case "ts_code":
                            stockInfo.setTsCode(item.getString(j));
                            break;
                        case "symbol":
                            stockInfo.setSymbol(item.getString(j));
                            break;
                        case "name":
                            stockInfo.setName(item.getString(j));
                            break;
                        case "area":
                            stockInfo.setArea(item.getString(j));
                            break;
                        case "industry":
                            stockInfo.setIndustry(item.getString(j));
                            break;
                        case "fullname":
                            stockInfo.setFullName(item.getString(j));
                            break;
                        case "enname":
                            stockInfo.setEnName(item.getString(j));
                            break;
                        case "cnspell":
                            stockInfo.setCnspell(item.getString(j));
                            break;
                        case "market":
                            stockInfo.setMarket(item.getString(j));
                            break;
                        case "exchange":
                            stockInfo.setExchange(item.getString(j));
                            break;
                        case "curr_type":
                            stockInfo.setCurrType(item.getString(j));
                            break;
                        case "list_status":
                            stockInfo.setListStatus(item.getString(j));
                            break;
                        case "list_date":
                            long listDateTimestamp = DateConvertUtils.convertDateStrToLong(item.getString(j), "yyyyMMdd");
                            stockInfo.setListDate(listDateTimestamp);
                            break;
                        case "delist_date":
                            String rawStr = item.getString(j);
                            if (rawStr == null) {
                                stockInfo.setDelistDate(null);
                                continue;
                            }
                            long delistDateTimestamp = DateConvertUtils.convertDateStrToLong(rawStr, "yyyyMMdd");
                            stockInfo.setDelistDate(delistDateTimestamp);
                            break;
                        case "is_hs":
                            stockInfo.setIsHs(item.getString(j));
                            break;
                        case "act_name":
                            stockInfo.setActName(item.getString(j));
                            break;
                        case "act_ent_type":
                            stockInfo.setActEntType(item.getString(j));
                            break;
                        default:
                            // Handle unknown fields if necessary
                            break;
                    }
                }
                stockInfoList.add(stockInfo);
            }
        } catch (Exception e) {
            log.error("Error occurred while converting raw TuShare data", e);
            return -1;
        }

        try ( SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH) ) {
            StockInfoDao stockInfoDao = sqlSession.getMapper(StockInfoDao.class);
            for (StockInfo stockInfo : stockInfoList) {
                stockInfoDao.insertStockInfo(stockInfo);
            }
            sqlSession.commit();
        } catch (Exception e) {
            log.error("Error occurred while storing stock info data", e);
            return -2;
        }

        return stockInfoList.size();
    }

    public int storeStockDailyByRawTuShareOutput(JSONArray data, JSONArray fields) {
        List<StockDaily> stockDailyList = new ArrayList<>();

        try {
            for (int i = 0; i < data.size(); i++) {
                StockDaily stockDaily = new StockDaily();
                JSONArray item = data.getJSONArray(i);
                for (int j = 0; j < fields.size(); j++) {
                    String field = fields.getString(j);
                    switch (field) {
                        case "ts_code":
                            stockDaily.setTsCode(item.getString(j));
                            break;
                        case "trade_date":
                            long tradeDateTimestamp = DateConvertUtils.convertDateStrToLong(item.getString(j), "yyyyMMdd");
                            stockDaily.setTradeDate(tradeDateTimestamp);
                            break;
                        case "close":
                            stockDaily.setClose(item.getDouble(j));
                            break;
                        case "open":
                            stockDaily.setOpen(item.getDouble(j));
                            break;
                        case "high":
                            stockDaily.setHigh(item.getDouble(j));
                            break;
                        case "low":
                            stockDaily.setLow(item.getDouble(j));
                            break;
                        case "pre_close":
                            stockDaily.setPreClose(item.getDouble(j));
                            break;
                        case "change":
                            stockDaily.setChange(item.getDouble(j));
                            break;
                        case "pct_chg":
                            stockDaily.setPctChg(item.getDouble(j));
                            break;
                        case "vol":
                            stockDaily.setVol(item.getDouble(j));
                            break;
                        case "amount":
                            stockDaily.setAmount(item.getDouble(j));
                            break;
                        default:
                            // Handle unknown fields if necessary
                            break;
                    }
                }
                stockDailyList.add(stockDaily);
            }
        } catch (Exception e) {
            log.error("Error occurred while converting raw TuShare data", e);
            return -1;
        }

        try ( SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH) ) {
            StockQuoteDao stockQuoteDao = sqlSession.getMapper(StockQuoteDao.class);
            for (StockDaily stockDaily : stockDailyList) {
                stockQuoteDao.insertStockDaily(stockDaily);
            }
            sqlSession.commit();
        } catch (Exception e) {
            log.error("Error occurred while storing stock daily data", e);
            return -2;
        }

        return stockDailyList.size();
    }
}
