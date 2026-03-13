package world.willfrog.alphafrogmicro.domestic.fetch.utils;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.common.dao.domestic.fund.*;
import world.willfrog.alphafrogmicro.common.pojo.domestic.fund.*;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class DomesticFundStoreUtils {

    private final SqlSessionFactory sqlSessionFactory;

    public DomesticFundStoreUtils(SqlSessionFactory sqlSessionFactory){
        this.sqlSessionFactory = sqlSessionFactory;
    }

    /**
     * 输入包含**所有列**的TuShare基金净值原始JSON数据，将其持久化到数据库中
     * 注意：列的排序必须和TuShare官方默认的列排序一致
     * 见 <a href="https://tushare.pro/document/2?doc_id=119">TuShare文档</a> 中的表格
     */
    public int storeFundNavsByRawFullTuShareOutput(JSONArray data) {

        List<FundNav> fundNavList = new ArrayList<>();

        try {
            for (int i = 0; i < data.size(); i++) {
                JSONArray item = data.getJSONArray(i);
                FundNav fundNav = new FundNav();
                fundNav.setTsCode(item.getString(0));

                String annDateStr = item.getString(1);
                fundNav.setAnnDate(annDateStr == null ? null : DateConvertUtils.convertDateStrToLong(annDateStr, "yyyyMMdd"));

                String navDateStr = item.getString(2);
                fundNav.setNavDate(navDateStr == null ? null : DateConvertUtils.convertDateStrToLong(navDateStr, "yyyyMMdd"));

                fundNav.setUnitNav(item.getDouble(3));
                fundNav.setAccumNav(item.getDouble(4));
                fundNav.setAccumDiv(item.getDouble(5));
                fundNav.setNetAsset(item.getDouble(6));
                fundNav.setTotalNetAsset(item.getDouble(7));
                fundNav.setAdjNav(item.getDouble(8));
                fundNavList.add(fundNav);
            }

            // 批量插入数据的写法，详见
            // https://github.com/mybatis/mybatis-3/wiki/FAQ#how-do-i-code-a-batch-insert
            // https://stackoverflow.com/questions/56513222/fastest-way-to-update-huge-number-of-rows-with-input-param-listt-in-mybatis-to/56515063#56515063
            try ( SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH) ) {
                FundNavDao fundNavDao = sqlSession.getMapper(FundNavDao.class);
                int i = 0;
                int batchSize = 50;
                for (FundNav fundNav : fundNavList) {
                    i++;
                    fundNavDao.insertFundNav(fundNav);
                    if (i % batchSize == 0 || i == fundNavList.size()) {
                        sqlSession.flushStatements();
                        sqlSession.clearCache();
                    }
                }
                sqlSession.commit();
            } catch (Exception e) {
                log.error("Error occurred while inserting fund nav data");
                log.error("Error trace", e);
                return -2;
            }

        } catch (Exception e) {
            log.error("Error occurred while converting fund nav raw data");
            log.error("Error trace", e);
            return -1;
        }
        return fundNavList.size();
    }

    public int storeFundInfosByRawTuShareOutput(JSONArray data) {

        List<FundInfo> fundInfoList = new ArrayList<>();

        int affectedRows = 0;

        try{
            for(int i = 0; i < data.size(); i++) {
                JSONArray item = data.getJSONArray(i);
                FundInfo fundInfo = new FundInfo();
                fundInfo.setTsCode(item.getString(0));
                fundInfo.setName(item.getString(1));
                fundInfo.setManagement(item.getString(2));
                fundInfo.setCustodian(item.getString(3));
                fundInfo.setFundType(item.getString(4));

                String fundDateStr = item.getString(5);
                fundInfo.setFoundDate(fundDateStr == null ? null : DateConvertUtils.convertDateStrToLong(fundDateStr, "yyyyMMdd"));

                String dueDateStr = item.getString(6);
                fundInfo.setDueDate(dueDateStr == null ? null : DateConvertUtils.convertDateStrToLong(dueDateStr, "yyyyMMdd"));

                String listDateStr = item.getString(7);
                fundInfo.setListDate(listDateStr == null ? null : DateConvertUtils.convertDateStrToLong(listDateStr, "yyyyMMdd"));

                String issueDateStr = item.getString(8);
                fundInfo.setIssueDate(issueDateStr == null ? null : DateConvertUtils.convertDateStrToLong(issueDateStr, "yyyyMMdd"));

                String delistDateStr = item.getString(9);
                fundInfo.setDelistDate(delistDateStr == null ? null : DateConvertUtils.convertDateStrToLong(delistDateStr, "yyyyMMdd"));

                fundInfo.setIssueAmount(item.getDouble(10));
                fundInfo.setMFee(item.getDouble(11));
                fundInfo.setCFee(item.getDouble(12));
                fundInfo.setDurationYear(item.getDouble(13));
                fundInfo.setPValue(item.getDouble(14));
                fundInfo.setMinAmount(item.getDouble(15));
                fundInfo.setExpReturn(item.getDouble(16));
                fundInfo.setBenchmark(item.getString(17));
                fundInfo.setStatus(item.getString(18));
                fundInfo.setInvestType(item.getString(19));
                fundInfo.setType(item.getString(20));
                fundInfo.setTrustee(item.getString(21));

                String purcStartDate = item.getString(22);
                fundInfo.setPurcStartDate(purcStartDate == null ? null : DateConvertUtils.convertDateStrToLong(purcStartDate, "yyyyMMdd"));

                String redmStartDate = item.getString(23);
                fundInfo.setRedmStartDate(redmStartDate == null ? null : DateConvertUtils.convertDateStrToLong(redmStartDate, "yyyyMMdd"));

                fundInfo.setMarket(item.getString(24));
                fundInfoList.add(fundInfo);
            }

            int batchSize = 5;
            try ( SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
                FundInfoDao fundInfoDao = sqlSession.getMapper(FundInfoDao.class);
                for (FundInfo fundInfo : fundInfoList) {
                    affectedRows++;
                    fundInfoDao.insertFundInfo(fundInfo);
                    if (affectedRows % batchSize == 0 || affectedRows == fundInfoList.size()) {
                        sqlSession.flushStatements();
                        sqlSession.clearCache();
                    }
                }
                sqlSession.commit();
            } catch (Exception e) {
                log.error("Error occurred while inserting fund info data");
                log.error("Error trace", e);
                return -2;
            }

        } catch (Exception e) {
            log.error("Error occurred while converting fund info raw data");
            log.error("Error trace", e);
            return -1;
        }

        return affectedRows;
    }



    public int storeFundPortfoliosByRawTuShareOutput(JSONArray data) {
        List<FundPortfolio> fundPortfolioList = new ArrayList<>();

        int affectedRows = 0;
        int batchSize = 50;

        try {
            for (int i = 0; i < data.size(); i++) {
                JSONArray item = data.getJSONArray(i);
                FundPortfolio fundPortfolio = new FundPortfolio();
                fundPortfolio.setTsCode(item.getString(0));

                String annDateStr = item.getString(1);
                fundPortfolio.setAnnDate(annDateStr == null ? null : DateConvertUtils.convertDateStrToLong(annDateStr, "yyyyMMdd"));

                String endDateStr = item.getString(2);
                fundPortfolio.setEndDate(endDateStr == null ? null : DateConvertUtils.convertDateStrToLong(endDateStr, "yyyyMMdd"));

                fundPortfolio.setSymbol(item.getString(3));
                fundPortfolio.setMkv(item.getDouble(4));
                fundPortfolio.setAmount(item.getDouble(5));
                fundPortfolio.setStkMkvRatio(item.getDouble(6));
                fundPortfolio.setStkFloatRatio(item.getDouble(7));

                fundPortfolioList.add(fundPortfolio);
            }

            try ( SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH) ) {
                FundPortfolioDao fundPortfolioDao = sqlSession.getMapper(FundPortfolioDao.class);
                for (FundPortfolio fundPortfolio : fundPortfolioList) {
                    affectedRows++;
                    fundPortfolioDao.insertFundPortfolio(fundPortfolio);
                    if (affectedRows % batchSize == 0 || affectedRows == fundPortfolioList.size()) {
                        sqlSession.flushStatements();
                        sqlSession.clearCache();
                    }
                }
                sqlSession.commit();
            } catch (Exception e) {
                log.error("Error occured while inserting fund portfolio data");
                log.error("Error trace", e);
                return -2;
            }
        } catch (Exception e) {
            System.out.println("Error occured while converting fund portfolio raw data");
            log.error("Error trace", e);
            return -1; // Indicate failure
        }

        return affectedRows;
    }


    public int storeFundCompanyByRawTuShareOutput(JSONArray data, JSONArray fields) {
        List<FundCompany> list = new ArrayList<>();

        try {
            for (int i = 0; i < data.size(); i++) {
                JSONArray item = data.getJSONArray(i);
                FundCompany pojo = new FundCompany();
                JSONObject ext = new JSONObject();
                for (int j = 0; j < fields.size(); j++) {
                    String field = fields.getString(j);
                    switch (field) {
                        case "name":
                            pojo.setName(item.getString(j));
                            break;
                        case "shortname":
                            pojo.setShortname(item.getString(j));
                            break;
                        case "short_enname":
                            pojo.setShortEnname(item.getString(j));
                            break;
                        case "province":
                            pojo.setProvince(item.getString(j));
                            break;
                        case "city":
                            pojo.setCity(item.getString(j));
                            break;
                        case "address":
                            pojo.setAddress(item.getString(j));
                            break;
                        case "phone":
                            pojo.setPhone(item.getString(j));
                            break;
                        case "office":
                            pojo.setOffice(item.getString(j));
                            break;
                        case "website":
                            pojo.setWebsite(item.getString(j));
                            break;
                        case "chairman":
                            pojo.setChairman(item.getString(j));
                            break;
                        case "manager":
                            pojo.setManager(item.getString(j));
                            break;
                        case "reg_capital":
                            BigDecimal regCapital = item.getBigDecimal(j);
                            if (regCapital != null) pojo.setRegCapital(regCapital.doubleValue());
                            break;
                        case "setup_date":
                            String setupDateStr = item.getString(j);
                            if (setupDateStr != null && !setupDateStr.trim().isEmpty()) {
                                pojo.setSetupDate(DateConvertUtils.convertFlexibleDateStrToLong(setupDateStr));
                            }
                            break;
                        case "end_date":
                            String endDateStr = item.getString(j);
                            if (endDateStr != null && !endDateStr.trim().isEmpty()) {
                                pojo.setEndDate(DateConvertUtils.convertFlexibleDateStrToLong(endDateStr));
                            }
                            break;
                        case "employees":
                            BigDecimal employees = item.getBigDecimal(j);
                            if (employees != null) pojo.setEmployees(employees.doubleValue());
                            break;
                        case "main_business":
                            pojo.setMainBusiness(item.getString(j));
                            break;
                        case "org_code":
                            pojo.setOrgCode(item.getString(j));
                            break;
                        case "credit_code":
                            pojo.setCreditCode(item.getString(j));
                            break;
                        default:
                            ext.put(field, item.get(j));
                            break;
                    }
                }
                if (!ext.isEmpty()) {
                    pojo.setExtended(ext.toJSONString());
                }
                list.add(pojo);
            }
        } catch (Exception e) {
            log.error("Error occurred while converting raw TuShare data to FundCompany", e);
            return -1;
        }

        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
            FundCompanyDao dao = sqlSession.getMapper(FundCompanyDao.class);
            for (FundCompany item : list) {
                dao.insertFundCompany(item);
            }
            sqlSession.commit();
        } catch (Exception e) {
            log.error("Error occurred while inserting FundCompany data", e);
            return -2;
        }

        return list.size();
    }


    public int storeFundManagerByRawTuShareOutput(JSONArray data, JSONArray fields) {
        List<FundManager> list = new ArrayList<>();

        try {
            for (int i = 0; i < data.size(); i++) {
                JSONArray item = data.getJSONArray(i);
                FundManager pojo = new FundManager();
                JSONObject ext = new JSONObject();
                for (int j = 0; j < fields.size(); j++) {
                    String field = fields.getString(j);
                    switch (field) {
                        case "ts_code":
                            pojo.setTsCode(item.getString(j));
                            break;
                        case "ann_date":
                            String annDateStr = item.getString(j);
                            if (annDateStr != null) {
                                pojo.setAnnDate(DateConvertUtils.convertDateStrToLong(annDateStr, "yyyyMMdd"));
                            }
                            break;
                        case "name":
                            pojo.setName(item.getString(j));
                            break;
                        case "gender":
                            pojo.setGender(item.getString(j));
                            break;
                        case "birth_year":
                            pojo.setBirthYear(item.getString(j));
                            break;
                        case "edu":
                            pojo.setEdu(item.getString(j));
                            break;
                        case "nationality":
                            pojo.setNationality(item.getString(j));
                            break;
                        case "begin_date":
                            String beginDateStr = item.getString(j);
                            if (beginDateStr != null) {
                                pojo.setBeginDate(DateConvertUtils.convertDateStrToLong(beginDateStr, "yyyyMMdd"));
                            }
                            break;
                        case "end_date":
                            String endDateStr = item.getString(j);
                            if (endDateStr != null) {
                                pojo.setEndDate(DateConvertUtils.convertDateStrToLong(endDateStr, "yyyyMMdd"));
                            }
                            break;
                        case "resume":
                            pojo.setResume(item.getString(j));
                            break;
                        default:
                            ext.put(field, item.get(j));
                            break;
                    }
                }
                if (!ext.isEmpty()) {
                    pojo.setExtended(ext.toJSONString());
                }
                list.add(pojo);
            }
        } catch (Exception e) {
            log.error("Error occurred while converting raw TuShare data to FundManager", e);
            return -1;
        }

        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
            FundManagerDao dao = sqlSession.getMapper(FundManagerDao.class);
            int i = 0;
            int batchSize = 50;
            for (FundManager item : list) {
                i++;
                dao.insertFundManager(item);
                if (i % batchSize == 0 || i == list.size()) {
                    sqlSession.flushStatements();
                    sqlSession.clearCache();
                }
            }
            sqlSession.commit();
        } catch (Exception e) {
            log.error("Error occurred while inserting FundManager data", e);
            return -2;
        }

        return list.size();
    }


    public int storeFundShareByRawTuShareOutput(JSONArray data, JSONArray fields) {
        List<FundShare> list = new ArrayList<>();

        try {
            for (int i = 0; i < data.size(); i++) {
                JSONArray item = data.getJSONArray(i);
                FundShare pojo = new FundShare();
                JSONObject ext = new JSONObject();
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
                        case "fd_share":
                            BigDecimal fdShare = item.getBigDecimal(j);
                            if (fdShare != null) pojo.setFdShare(fdShare.doubleValue());
                            break;
                        default:
                            ext.put(field, item.get(j));
                            break;
                    }
                }
                if (!ext.isEmpty()) {
                    pojo.setExtended(ext.toJSONString());
                }
                list.add(pojo);
            }
        } catch (Exception e) {
            log.error("Error occurred while converting raw TuShare data to FundShare", e);
            return -1;
        }

        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
            FundShareDao dao = sqlSession.getMapper(FundShareDao.class);
            int i = 0;
            int batchSize = 50;
            for (FundShare item : list) {
                i++;
                dao.insertFundShare(item);
                if (i % batchSize == 0 || i == list.size()) {
                    sqlSession.flushStatements();
                    sqlSession.clearCache();
                }
            }
            sqlSession.commit();
        } catch (Exception e) {
            log.error("Error occurred while inserting FundShare data", e);
            return -2;
        }

        return list.size();
    }


    public int storeEtfShareSizeByRawTuShareOutput(JSONArray data, JSONArray fields) {
        List<EtfShareSize> list = new ArrayList<>();

        try {
            for (int i = 0; i < data.size(); i++) {
                JSONArray item = data.getJSONArray(i);
                EtfShareSize pojo = new EtfShareSize();
                JSONObject ext = new JSONObject();
                for (int j = 0; j < fields.size(); j++) {
                    String field = fields.getString(j);
                    switch (field) {
                        case "trade_date":
                            String tradeDateStr = item.getString(j);
                            if (tradeDateStr != null) {
                                pojo.setTradeDate(DateConvertUtils.convertDateStrToLong(tradeDateStr, "yyyyMMdd"));
                            }
                            break;
                        case "ts_code":
                            pojo.setTsCode(item.getString(j));
                            break;
                        case "etf_name":
                            pojo.setEtfName(item.getString(j));
                            break;
                        case "total_share":
                            BigDecimal totalShare = item.getBigDecimal(j);
                            if (totalShare != null) pojo.setTotalShare(totalShare.doubleValue());
                            break;
                        case "total_size":
                            BigDecimal totalSize = item.getBigDecimal(j);
                            if (totalSize != null) pojo.setTotalSize(totalSize.doubleValue());
                            break;
                        case "nav":
                            BigDecimal nav = item.getBigDecimal(j);
                            if (nav != null) pojo.setNav(nav.doubleValue());
                            break;
                        case "close":
                            BigDecimal close = item.getBigDecimal(j);
                            if (close != null) pojo.setClose(close.doubleValue());
                            break;
                        case "exchange":
                            pojo.setExchange(item.getString(j));
                            break;
                        default:
                            ext.put(field, item.get(j));
                            break;
                    }
                }
                if (!ext.isEmpty()) {
                    pojo.setExtended(ext.toJSONString());
                }
                list.add(pojo);
            }
        } catch (Exception e) {
            log.error("Error occurred while converting raw TuShare data to EtfShareSize", e);
            return -1;
        }

        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
            EtfShareSizeDao dao = sqlSession.getMapper(EtfShareSizeDao.class);
            int i = 0;
            int batchSize = 50;
            for (EtfShareSize item : list) {
                i++;
                dao.insertEtfShareSize(item);
                if (i % batchSize == 0 || i == list.size()) {
                    sqlSession.flushStatements();
                    sqlSession.clearCache();
                }
            }
            sqlSession.commit();
        } catch (Exception e) {
            log.error("Error occurred while inserting EtfShareSize data", e);
            return -2;
        }

        return list.size();
    }

}
