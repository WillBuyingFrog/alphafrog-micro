package world.willfrog.alphafrogmicro.domestic.fetch.utils;

import com.alibaba.fastjson.JSONArray;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.common.dao.domestic.fund.FundNavDao;
import world.willfrog.alphafrogmicro.common.pojo.domestic.fund.FundNav;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;

import java.util.ArrayList;
import java.util.List;

@Component
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
                System.out.println("Error occurred while inserting fund nav data");
                e.printStackTrace();
                return -2;
            }

        } catch (Exception e) {
            System.out.println("Error occurred while converting fund nav raw data");
            e.printStackTrace();
            return -1;
        }
        return fundNavList.size();
    }
}
