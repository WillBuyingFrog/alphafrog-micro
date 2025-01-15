package world.willfrog.alphafrogmicro.domestic.fetch.utils;

import com.alibaba.fastjson.JSONArray;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.IndexInfoDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.IndexQuoteDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.IndexWeightDao;
import world.willfrog.alphafrogmicro.common.pojo.domestic.index.IndexDaily;
import world.willfrog.alphafrogmicro.common.pojo.domestic.index.IndexInfo;
import world.willfrog.alphafrogmicro.common.pojo.domestic.index.IndexWeight;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class DomesticIndexStoreUtils {

    private final SqlSessionFactory sqlSessionFactory;


    public DomesticIndexStoreUtils(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = sqlSessionFactory;
    }



    public int storeIndexInfoByRawTuShareOutput(JSONArray data, JSONArray fields){

        List<IndexInfo> indexInfoList = new ArrayList<>();


        try {
            for (int i = 0; i < data.size(); i++) {
                IndexInfo indexInfo = new IndexInfo();
                JSONArray item = data.getJSONArray(i);
                for (int j = 0; j < fields.size(); j++) {
                    String field = fields.getString(j);
                    switch (field) {
                        case "ts_code":
                            indexInfo.setTsCode(item.getString(j));
                            break;
                        case "name":
                            indexInfo.setName(item.getString(j));
                            break;
                        case "fullname":
                            indexInfo.setFullName(item.getString(j));
                            break;
                        case "market":
                            indexInfo.setMarket(item.getString(j));
                            break;
                        case "publisher":
                            indexInfo.setPublisher(item.getString(j));
                            break;
                        case "index_type":
                            indexInfo.setIndexType(item.getString(j));
                            break;
                        case "category":
                            indexInfo.setCategory(item.getString(j));
                            break;
                        case "base_date":
                            String baseDateStr = item.getString(j);
                            if (baseDateStr == null) {
                                indexInfo.setBaseDate(null);
                            } else {
                                Long baseDate = DateConvertUtils.convertDateStrToLong(baseDateStr, "yyyyMMdd");
                                indexInfo.setBaseDate(baseDate);
                            }
                            break;
                        case "base_point":
                            BigDecimal basePointDecimal = item.getBigDecimal(j);
                            if (basePointDecimal == null) {
                                indexInfo.setBasePoint(null);
                            } else {
                                indexInfo.setBasePoint(basePointDecimal.doubleValue());
                            }
                            break;
                        case "list_date":
                            String listDateStr = item.getString(j);
                            if (listDateStr == null) {
                                indexInfo.setListDate(null);
                            } else {
                                Long listDate = DateConvertUtils.convertDateStrToLong(listDateStr, "yyyyMMdd");
                                indexInfo.setListDate(listDate);
                            }
                            break;
                        case "weight_rule":
                            indexInfo.setWeightRule(item.getString(j));
                            break;
                        case "desc":
                            indexInfo.setDesc(item.getString(j));
                            break;
                        case "exp_date":
                            String expDateStr = item.getString(j);
                            if (expDateStr == null) {
                                indexInfo.setExpDate(null);
                            } else {
                                Long expDate = DateConvertUtils.convertDateStrToLong(expDateStr, "yyyyMMdd");
                                indexInfo.setExpDate(expDate);
                            }

                            break;
                        default:
                            break;
                    }
                }
                indexInfoList.add(indexInfo);
            }
        } catch (Exception e) {
            log.error("Error occurred while converting raw TuShare data", e);
            return -1;
        }

        try ( SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
            IndexInfoDao indexInfoDao = sqlSession.getMapper(IndexInfoDao.class);
            for (IndexInfo indexInfo : indexInfoList) {
                indexInfoDao.insertIndexInfo(indexInfo);
            }
            sqlSession.commit();
        } catch (Exception e) {
            log.error("Error occurred while inserting index info data", e);
            return -2;
        }

        return indexInfoList.size();
    }


    public int storeIndexDailyByRawTuShareOutput(JSONArray data, JSONArray fields) {
        List<IndexDaily> indexDailyList = new ArrayList<>();

        try {
            for(int i = 0; i < data.size(); i++) {
                JSONArray item = data.getJSONArray(i);
                IndexDaily indexDaily = new IndexDaily();
                for (int j = 0; j < fields.size(); j++) {
                    String field = fields.getString(j);
                    switch (field) {
                        case "ts_code":
                            indexDaily.setTsCode(item.getString(j));
                            break;
                        case "trade_date":
                            String tradeDateStr = item.getString(j);
                            Long tradeDate = DateConvertUtils.convertDateStrToLong(tradeDateStr, "yyyyMMdd");
                            indexDaily.setTradeDate(tradeDate);
                            break;
                        case "close":
                            indexDaily.setClose(item.getBigDecimal(j).doubleValue());
                            break;
                        case "open":
                            indexDaily.setOpen(item.getBigDecimal(j).doubleValue());
                            break;
                        case "high":
                            indexDaily.setHigh(item.getBigDecimal(j).doubleValue());
                            break;
                        case "low":
                            indexDaily.setLow(item.getBigDecimal(j).doubleValue());
                            break;
                        case "pre_close":
                            indexDaily.setPreClose(item.getBigDecimal(j).doubleValue());
                            break;
                        case "change":
                            indexDaily.setChange(item.getBigDecimal(j).doubleValue());
                            break;
                        case "pct_chg":
                            indexDaily.setPctChg(item.getBigDecimal(j).doubleValue());
                            break;
                        case "vol":
                            indexDaily.setVol(item.getBigDecimal(j).doubleValue());
                            break;
                        case "amount":
                            indexDaily.setAmount(item.getBigDecimal(j).doubleValue());
                            break;
                        default:
                            break;
                    }
                }
                indexDailyList.add(indexDaily);
            }
        } catch (Exception e){
            log.error("Error occurred while converting raw TuShare data to IndexDaily", e);
            return -1;
        }
        int totalAffected = 0;
        int batchSize = 50;
        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
            IndexQuoteDao dao = sqlSession.getMapper(IndexQuoteDao.class);
            for (IndexDaily daily : indexDailyList) {
                totalAffected++;
                dao.insertIndexDaily(daily);
                if (totalAffected % batchSize == 0 || totalAffected == indexDailyList.size()) {
                    log.info("Flushing all statements");
                    sqlSession.flushStatements();
                    sqlSession.clearCache();
                }
            }
            sqlSession.commit();
        } catch ( Exception e ){
            log.error("Error occurred while inserting index daily data", e);
            return -2;
        }

        return totalAffected;
    }

    public int storeIndexWeightByRawTuShareOutput(JSONArray data, JSONArray fields) {

        List<IndexWeight> indexWeightList = new ArrayList<>();

        try {
            for (int i = 0; i < data.size(); i++) {
                JSONArray item = data.getJSONArray(i);
                IndexWeight indexWeight = new IndexWeight();
                for (int j = 0; j < fields.size(); j++) {
                    String field = fields.getString(j);
                    switch (field) {
                        case "index_code":
                            indexWeight.setIndexCode(item.getString(j));
                            break;
                        case "con_code":
                            indexWeight.setConCode(item.getString(j));
                            break;
                        case "trade_date":
                            String tradeDateStr = item.getString(j);
                            Long tradeDate = DateConvertUtils.convertDateStrToLong(tradeDateStr, "yyyyMMdd");
                            indexWeight.setTradeDate(tradeDate);
                            break;
                        case "weight":
                            indexWeight.setWeight(item.getBigDecimal(j).doubleValue());
                            break;
                        default:
                            break;
                    }
                }
                indexWeightList.add(indexWeight);
            }
        } catch (Exception e) {
            log.error("Error occurred while converting raw TuShare data to IndexWeight", e);
            return -1;
        }

        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
            IndexWeightDao indexWeightDao = sqlSession.getMapper(IndexWeightDao.class);
            for (IndexWeight indexWeight : indexWeightList) {
                indexWeightDao.insertIndexWeight(indexWeight);
            }
            sqlSession.commit();
        } catch (Exception e) {
            log.error("Error occurred while inserting index weight data", e);
            return -2;
        }

        return indexWeightList.size();
    }



}
