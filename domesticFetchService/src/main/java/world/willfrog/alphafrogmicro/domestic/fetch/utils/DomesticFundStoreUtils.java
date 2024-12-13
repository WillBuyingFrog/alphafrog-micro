package world.willfrog.alphafrogmicro.domestic.fetch.utils;

import com.alibaba.fastjson.JSONArray;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.common.dao.domestic.fund.FundInfoDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.fund.FundNavDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.fund.FundPortfolioDao;
import world.willfrog.alphafrogmicro.common.pojo.domestic.fund.FundInfo;
import world.willfrog.alphafrogmicro.common.pojo.domestic.fund.FundNav;
import world.willfrog.alphafrogmicro.common.pojo.domestic.fund.FundPortfolio;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;

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
            try ( SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH) ) {
                FundNavDao fundNavDao = sqlSession.getMapper(FundNavDao.class);
                for (FundNav fundNav : fundNavList) {
                    fundNavDao.insertFundNav(fundNav);
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

            try ( SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
                FundInfoDao fundInfoDao = sqlSession.getMapper(FundInfoDao.class);
                for (FundInfo fundInfo : fundInfoList) {
                    affectedRows += fundInfoDao.insertFundInfo(fundInfo);
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
                    affectedRows += fundPortfolioDao.insertFundPortfolio(fundPortfolio);
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
}
