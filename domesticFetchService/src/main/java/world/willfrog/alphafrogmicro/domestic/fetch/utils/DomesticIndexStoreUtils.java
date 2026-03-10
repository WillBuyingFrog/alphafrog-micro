package world.willfrog.alphafrogmicro.domestic.fetch.utils;

import com.alibaba.fastjson.JSONArray;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.*;
import world.willfrog.alphafrogmicro.common.pojo.domestic.index.*;
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
                            if (item.getBigDecimal(j) == null) {
                                indexDaily.setOpen(null);
                            } else {
                                indexDaily.setOpen(item.getBigDecimal(j).doubleValue());
                            }
                            break;
                        case "high":
                            if (item.getBigDecimal(j) == null) {
                                indexDaily.setHigh(null);
                            } else {
                                indexDaily.setHigh(item.getBigDecimal(j).doubleValue());
                            }
                            break;
                        case "low":
                            if (item.getBigDecimal(j) == null) {
                                indexDaily.setLow(null);
                            } else {
                                indexDaily.setLow(item.getBigDecimal(j).doubleValue());
                            }
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
                            if (item.getBigDecimal(j) == null) {
                                indexDaily.setVol(null);
                            } else {
                                indexDaily.setVol(item.getBigDecimal(j).doubleValue());
                            }
                            break;
                        case "amount":
                            if (item.getBigDecimal(j) == null) {
                                indexDaily.setAmount(null);
                            } else {
                                indexDaily.setAmount(item.getBigDecimal(j).doubleValue());
                            }
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
//        String[] tsCodes = new String[indexDailyList.size()];
        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH, false)) {
            IndexQuoteDao dao = sqlSession.getMapper(IndexQuoteDao.class);
            for (IndexDaily daily : indexDailyList) {
//                tsCodes[totalAffected] = daily.getTsCode();
                totalAffected++;
                dao.insertIndexDaily(daily);
                if (totalAffected % batchSize == 0 || totalAffected == indexDailyList.size()) {
                    sqlSession.commit();
                }
            }
            sqlSession.commit();
//            log.info("All TsCodes: {}", tsCodes);
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


    public int storeIndexDailyBasicByRawTuShareOutput(JSONArray data, JSONArray fields) {
        List<IndexDailyBasic> list = new ArrayList<>();

        try {
            for (int i = 0; i < data.size(); i++) {
                JSONArray item = data.getJSONArray(i);
                IndexDailyBasic pojo = new IndexDailyBasic();
                for (int j = 0; j < fields.size(); j++) {
                    String field = fields.getString(j);
                    switch (field) {
                        case "ts_code":
                            pojo.setTsCode(item.getString(j));
                            break;
                        case "trade_date":
                            String tradeDateStr = item.getString(j);
                            if (tradeDateStr != null) {
                                pojo.setTradeDate(DateConvertUtils.convertDateStrToLong(tradeDateStr, "yyyyMMdd"));
                            }
                            break;
                        case "total_mv":
                            BigDecimal totalMv = item.getBigDecimal(j);
                            if (totalMv != null) pojo.setTotalMv(totalMv.doubleValue());
                            break;
                        case "float_mv":
                            BigDecimal floatMv = item.getBigDecimal(j);
                            if (floatMv != null) pojo.setFloatMv(floatMv.doubleValue());
                            break;
                        case "total_share":
                            BigDecimal totalShare = item.getBigDecimal(j);
                            if (totalShare != null) pojo.setTotalShare(totalShare.doubleValue());
                            break;
                        case "float_share":
                            BigDecimal floatShare = item.getBigDecimal(j);
                            if (floatShare != null) pojo.setFloatShare(floatShare.doubleValue());
                            break;
                        case "free_share":
                            BigDecimal freeShare = item.getBigDecimal(j);
                            if (freeShare != null) pojo.setFreeShare(freeShare.doubleValue());
                            break;
                        case "turnover_rate":
                            BigDecimal turnoverRate = item.getBigDecimal(j);
                            if (turnoverRate != null) pojo.setTurnoverRate(turnoverRate.doubleValue());
                            break;
                        case "turnover_rate_f":
                            BigDecimal turnoverRateF = item.getBigDecimal(j);
                            if (turnoverRateF != null) pojo.setTurnoverRateF(turnoverRateF.doubleValue());
                            break;
                        case "pe":
                            BigDecimal pe = item.getBigDecimal(j);
                            if (pe != null) pojo.setPe(pe.doubleValue());
                            break;
                        case "pe_ttm":
                            BigDecimal peTtm = item.getBigDecimal(j);
                            if (peTtm != null) pojo.setPeTtm(peTtm.doubleValue());
                            break;
                        case "pb":
                            BigDecimal pb = item.getBigDecimal(j);
                            if (pb != null) pojo.setPb(pb.doubleValue());
                            break;
                        default:
                            break;
                    }
                }
                list.add(pojo);
            }
        } catch (Exception e) {
            log.error("Error occurred while converting raw TuShare data to IndexDailyBasic", e);
            return -1;
        }

        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
            IndexDailyBasicDao dao = sqlSession.getMapper(IndexDailyBasicDao.class);
            for (IndexDailyBasic item : list) {
                dao.insertIndexDailyBasic(item);
            }
            sqlSession.commit();
        } catch (Exception e) {
            log.error("Error occurred while inserting IndexDailyBasic data", e);
            return -2;
        }

        return list.size();
    }


    public int storeSwIndustryClassifyByRawTuShareOutput(JSONArray data, JSONArray fields) {
        List<SwIndustryClassify> list = new ArrayList<>();

        try {
            for (int i = 0; i < data.size(); i++) {
                JSONArray item = data.getJSONArray(i);
                SwIndustryClassify pojo = new SwIndustryClassify();
                for (int j = 0; j < fields.size(); j++) {
                    String field = fields.getString(j);
                    switch (field) {
                        case "index_code":
                            pojo.setIndexCode(item.getString(j));
                            break;
                        case "industry_name":
                            pojo.setIndustryName(item.getString(j));
                            break;
                        case "parent_code":
                            pojo.setParentCode(item.getString(j));
                            break;
                        case "level":
                            pojo.setLevel(item.getString(j));
                            break;
                        case "industry_code":
                            pojo.setIndustryCode(item.getString(j));
                            break;
                        case "is_pub":
                            pojo.setIsPub(item.getString(j));
                            break;
                        case "src":
                            pojo.setSrc(item.getString(j));
                            break;
                        default:
                            break;
                    }
                }
                list.add(pojo);
            }
        } catch (Exception e) {
            log.error("Error occurred while converting raw TuShare data to SwIndustryClassify", e);
            return -1;
        }

        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
            SwIndustryClassifyDao dao = sqlSession.getMapper(SwIndustryClassifyDao.class);
            for (SwIndustryClassify item : list) {
                dao.insertSwIndustryClassify(item);
            }
            sqlSession.commit();
        } catch (Exception e) {
            log.error("Error occurred while inserting SwIndustryClassify data", e);
            return -2;
        }

        return list.size();
    }


    public int storeSwIndustryMemberByRawTuShareOutput(JSONArray data, JSONArray fields) {
        List<SwIndustryMember> list = new ArrayList<>();

        try {
            for (int i = 0; i < data.size(); i++) {
                JSONArray item = data.getJSONArray(i);
                SwIndustryMember pojo = new SwIndustryMember();
                for (int j = 0; j < fields.size(); j++) {
                    String field = fields.getString(j);
                    switch (field) {
                        case "l1_code":
                            pojo.setL1Code(item.getString(j));
                            break;
                        case "l1_name":
                            pojo.setL1Name(item.getString(j));
                            break;
                        case "l2_code":
                            pojo.setL2Code(item.getString(j));
                            break;
                        case "l2_name":
                            pojo.setL2Name(item.getString(j));
                            break;
                        case "l3_code":
                            pojo.setL3Code(item.getString(j));
                            break;
                        case "l3_name":
                            pojo.setL3Name(item.getString(j));
                            break;
                        case "ts_code":
                            pojo.setTsCode(item.getString(j));
                            break;
                        case "name":
                            pojo.setName(item.getString(j));
                            break;
                        case "in_date":
                            String inDateStr = item.getString(j);
                            if (inDateStr != null) {
                                pojo.setInDate(DateConvertUtils.convertDateStrToLong(inDateStr, "yyyyMMdd"));
                            }
                            break;
                        case "out_date":
                            String outDateStr = item.getString(j);
                            if (outDateStr != null) {
                                pojo.setOutDate(DateConvertUtils.convertDateStrToLong(outDateStr, "yyyyMMdd"));
                            }
                            break;
                        case "is_new":
                            pojo.setIsNew(item.getString(j));
                            break;
                        default:
                            break;
                    }
                }
                list.add(pojo);
            }
        } catch (Exception e) {
            log.error("Error occurred while converting raw TuShare data to SwIndustryMember", e);
            return -1;
        }

        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
            SwIndustryMemberDao dao = sqlSession.getMapper(SwIndustryMemberDao.class);
            for (SwIndustryMember item : list) {
                dao.insertSwIndustryMember(item);
            }
            sqlSession.commit();
        } catch (Exception e) {
            log.error("Error occurred while inserting SwIndustryMember data", e);
            return -2;
        }

        return list.size();
    }


    public int storeSwIndustryDailyByRawTuShareOutput(JSONArray data, JSONArray fields) {
        List<SwIndustryDaily> list = new ArrayList<>();

        try {
            for (int i = 0; i < data.size(); i++) {
                JSONArray item = data.getJSONArray(i);
                SwIndustryDaily pojo = new SwIndustryDaily();
                for (int j = 0; j < fields.size(); j++) {
                    String field = fields.getString(j);
                    switch (field) {
                        case "ts_code":
                            pojo.setTsCode(item.getString(j));
                            break;
                        case "trade_date":
                            String tradeDateStr = item.getString(j);
                            if (tradeDateStr != null) {
                                pojo.setTradeDate(DateConvertUtils.convertDateStrToLong(tradeDateStr, "yyyyMMdd"));
                            }
                            break;
                        case "name":
                            pojo.setName(item.getString(j));
                            break;
                        case "open":
                            BigDecimal open = item.getBigDecimal(j);
                            if (open != null) pojo.setOpen(open.doubleValue());
                            break;
                        case "low":
                            BigDecimal low = item.getBigDecimal(j);
                            if (low != null) pojo.setLow(low.doubleValue());
                            break;
                        case "high":
                            BigDecimal high = item.getBigDecimal(j);
                            if (high != null) pojo.setHigh(high.doubleValue());
                            break;
                        case "close":
                            BigDecimal close = item.getBigDecimal(j);
                            if (close != null) pojo.setClose(close.doubleValue());
                            break;
                        case "change":
                            BigDecimal changeVal = item.getBigDecimal(j);
                            if (changeVal != null) pojo.setChangeVal(changeVal.doubleValue());
                            break;
                        case "pct_change":
                            BigDecimal pctChange = item.getBigDecimal(j);
                            if (pctChange != null) pojo.setPctChange(pctChange.doubleValue());
                            break;
                        case "vol":
                            BigDecimal vol = item.getBigDecimal(j);
                            if (vol != null) pojo.setVol(vol.doubleValue());
                            break;
                        case "amount":
                            BigDecimal amount = item.getBigDecimal(j);
                            if (amount != null) pojo.setAmount(amount.doubleValue());
                            break;
                        case "pe":
                            BigDecimal pe = item.getBigDecimal(j);
                            if (pe != null) pojo.setPe(pe.doubleValue());
                            break;
                        case "pb":
                            BigDecimal pb = item.getBigDecimal(j);
                            if (pb != null) pojo.setPb(pb.doubleValue());
                            break;
                        case "float_mv":
                            BigDecimal floatMv = item.getBigDecimal(j);
                            if (floatMv != null) pojo.setFloatMv(floatMv.doubleValue());
                            break;
                        case "total_mv":
                            BigDecimal totalMv = item.getBigDecimal(j);
                            if (totalMv != null) pojo.setTotalMv(totalMv.doubleValue());
                            break;
                        default:
                            break;
                    }
                }
                list.add(pojo);
            }
        } catch (Exception e) {
            log.error("Error occurred while converting raw TuShare data to SwIndustryDaily", e);
            return -1;
        }

        int totalAffected = 0;
        int batchSize = 50;
        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH, false)) {
            SwIndustryDailyDao dao = sqlSession.getMapper(SwIndustryDailyDao.class);
            for (SwIndustryDaily daily : list) {
                totalAffected++;
                dao.insertSwIndustryDaily(daily);
                if (totalAffected % batchSize == 0 || totalAffected == list.size()) {
                    sqlSession.commit();
                }
            }
            sqlSession.commit();
        } catch (Exception e) {
            log.error("Error occurred while inserting SwIndustryDaily data", e);
            return -2;
        }

        return totalAffected;
    }


    public int storeCiIndexMemberByRawTuShareOutput(JSONArray data, JSONArray fields) {
        List<CiIndexMember> list = new ArrayList<>();

        try {
            for (int i = 0; i < data.size(); i++) {
                JSONArray item = data.getJSONArray(i);
                CiIndexMember pojo = new CiIndexMember();
                for (int j = 0; j < fields.size(); j++) {
                    String field = fields.getString(j);
                    switch (field) {
                        case "l1_code":
                            pojo.setL1Code(item.getString(j));
                            break;
                        case "l1_name":
                            pojo.setL1Name(item.getString(j));
                            break;
                        case "l2_code":
                            pojo.setL2Code(item.getString(j));
                            break;
                        case "l2_name":
                            pojo.setL2Name(item.getString(j));
                            break;
                        case "l3_code":
                            pojo.setL3Code(item.getString(j));
                            break;
                        case "l3_name":
                            pojo.setL3Name(item.getString(j));
                            break;
                        case "ts_code":
                            pojo.setTsCode(item.getString(j));
                            break;
                        case "name":
                            pojo.setName(item.getString(j));
                            break;
                        case "in_date":
                            String inDateStr = item.getString(j);
                            if (inDateStr != null) {
                                pojo.setInDate(DateConvertUtils.convertDateStrToLong(inDateStr, "yyyyMMdd"));
                            }
                            break;
                        case "out_date":
                            String outDateStr = item.getString(j);
                            if (outDateStr != null) {
                                pojo.setOutDate(DateConvertUtils.convertDateStrToLong(outDateStr, "yyyyMMdd"));
                            }
                            break;
                        case "is_new":
                            pojo.setIsNew(item.getString(j));
                            break;
                        default:
                            break;
                    }
                }
                list.add(pojo);
            }
        } catch (Exception e) {
            log.error("Error occurred while converting raw TuShare data to CiIndexMember", e);
            return -1;
        }

        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
            CiIndexMemberDao dao = sqlSession.getMapper(CiIndexMemberDao.class);
            for (CiIndexMember item : list) {
                dao.insertCiIndexMember(item);
            }
            sqlSession.commit();
        } catch (Exception e) {
            log.error("Error occurred while inserting CiIndexMember data", e);
            return -2;
        }

        return list.size();
    }


}
