package world.willfrog.alphafrogmicro.domestic.fetch.utils;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.common.dao.domestic.stock.*;
import world.willfrog.alphafrogmicro.common.pojo.domestic.stock.*;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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

    // ==================== PR #35 新增：股票财务数据存储方法 ====================

    // 4.2 利润表
    private static final Set<String> STOCK_INCOME_CORE_FIELDS = Set.of(
            "ts_code", "ann_date", "f_ann_date", "end_date", "report_type", "comp_type", "end_type",
            "basic_eps", "diluted_eps", "total_revenue", "revenue", "total_cogs",
            "operate_profit", "total_profit", "n_income", "n_income_attr_p", "ebit", "ebitda", "rd_exp", "update_flag"
    );

    public int storeStockIncomeByRawTuShareOutput(JSONArray data, JSONArray fields) {
        List<StockIncome> list = new ArrayList<>();

        try {
            for (int i = 0; i < data.size(); i++) {
                JSONArray item = data.getJSONArray(i);
                StockIncome pojo = new StockIncome();
                JSONObject ext = new JSONObject();

                for (int j = 0; j < fields.size(); j++) {
                    String field = fields.getString(j);
                    String value = item.getString(j);

                    if (value == null || value.isEmpty()) continue;

                    switch (field) {
                        case "ts_code" -> pojo.setTsCode(value);
                        case "ann_date" -> pojo.setAnnDate(DateConvertUtils.convertDateStrToLong(value, "yyyyMMdd"));
                        case "f_ann_date" -> pojo.setFAnnDate(DateConvertUtils.convertDateStrToLong(value, "yyyyMMdd"));
                        case "end_date" -> pojo.setEndDate(DateConvertUtils.convertDateStrToLong(value, "yyyyMMdd"));
                        case "report_type" -> pojo.setReportType(value);
                        case "comp_type" -> pojo.setCompType(value);
                        case "end_type" -> pojo.setEndType(value);
                        case "basic_eps" -> pojo.setBasicEps(parseDouble(value));
                        case "diluted_eps" -> pojo.setDilutedEps(parseDouble(value));
                        case "total_revenue" -> pojo.setTotalRevenue(parseDouble(value));
                        case "revenue" -> pojo.setRevenue(parseDouble(value));
                        case "total_cogs" -> pojo.setTotalCogs(parseDouble(value));
                        case "operate_profit" -> pojo.setOperateProfit(parseDouble(value));
                        case "total_profit" -> pojo.setTotalProfit(parseDouble(value));
                        case "n_income" -> pojo.setNIncome(parseDouble(value));
                        case "n_income_attr_p" -> pojo.setNIncomeAttrP(parseDouble(value));
                        case "ebit" -> pojo.setEbit(parseDouble(value));
                        case "ebitda" -> pojo.setEbitda(parseDouble(value));
                        case "rd_exp" -> pojo.setRdExp(parseDouble(value));
                        case "update_flag" -> pojo.setUpdateFlag(value);
                        default -> {
                            if (!STOCK_INCOME_CORE_FIELDS.contains(field)) {
                                ext.put(field, value);
                            }
                        }
                    }
                }
                if (!ext.isEmpty()) {
                    pojo.setExtended(ext.toJSONString());
                }
                list.add(pojo);
            }
        } catch (Exception e) {
            log.error("Error converting StockIncome data", e);
            return -1;
        }

        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
            StockIncomeDao dao = sqlSession.getMapper(StockIncomeDao.class);
            for (StockIncome pojo : list) {
                dao.insertStockIncome(pojo);
            }
            sqlSession.commit();
        } catch (Exception e) {
            log.error("Error storing StockIncome data", e);
            return -2;
        }

        return list.size();
    }

    // 4.3 资产负债表
    private static final Set<String> STOCK_BALANCESHEET_CORE_FIELDS = Set.of(
            "ts_code", "ann_date", "f_ann_date", "end_date", "report_type", "comp_type", "end_type",
            "money_cap", "accounts_receiv", "inventories", "total_cur_assets",
            "fix_assets", "goodwill", "intan_assets", "r_and_d", "total_nca", "total_assets",
            "st_borr", "acct_payable", "total_cur_liab", "lt_borr", "bond_payable", "total_ncl", "total_liab",
            "total_hldr_eqy_exc_min_int", "total_hldr_eqy_inc_min_int", "minority_int", "update_flag"
    );

    public int storeStockBalancesheetByRawTuShareOutput(JSONArray data, JSONArray fields) {
        List<StockBalancesheet> list = new ArrayList<>();

        try {
            for (int i = 0; i < data.size(); i++) {
                JSONArray item = data.getJSONArray(i);
                StockBalancesheet pojo = new StockBalancesheet();
                JSONObject ext = new JSONObject();

                for (int j = 0; j < fields.size(); j++) {
                    String field = fields.getString(j);
                    String value = item.getString(j);

                    if (value == null || value.isEmpty()) continue;

                    switch (field) {
                        case "ts_code" -> pojo.setTsCode(value);
                        case "ann_date" -> pojo.setAnnDate(DateConvertUtils.convertDateStrToLong(value, "yyyyMMdd"));
                        case "f_ann_date" -> pojo.setFAnnDate(DateConvertUtils.convertDateStrToLong(value, "yyyyMMdd"));
                        case "end_date" -> pojo.setEndDate(DateConvertUtils.convertDateStrToLong(value, "yyyyMMdd"));
                        case "report_type" -> pojo.setReportType(value);
                        case "comp_type" -> pojo.setCompType(value);
                        case "end_type" -> pojo.setEndType(value);
                        case "money_cap" -> pojo.setMoneyCap(parseDouble(value));
                        case "accounts_receiv" -> pojo.setAccountsReceiv(parseDouble(value));
                        case "inventories" -> pojo.setInventories(parseDouble(value));
                        case "total_cur_assets" -> pojo.setTotalCurAssets(parseDouble(value));
                        case "fix_assets" -> pojo.setFixAssets(parseDouble(value));
                        case "goodwill" -> pojo.setGoodwill(parseDouble(value));
                        case "intan_assets" -> pojo.setIntanAssets(parseDouble(value));
                        case "r_and_d" -> pojo.setRAndD(parseDouble(value));
                        case "total_nca" -> pojo.setTotalNca(parseDouble(value));
                        case "total_assets" -> pojo.setTotalAssets(parseDouble(value));
                        case "st_borr" -> pojo.setStBorr(parseDouble(value));
                        case "acct_payable" -> pojo.setAcctPayable(parseDouble(value));
                        case "total_cur_liab" -> pojo.setTotalCurLiab(parseDouble(value));
                        case "lt_borr" -> pojo.setLtBorr(parseDouble(value));
                        case "bond_payable" -> pojo.setBondPayable(parseDouble(value));
                        case "total_ncl" -> pojo.setTotalNcl(parseDouble(value));
                        case "total_liab" -> pojo.setTotalLiab(parseDouble(value));
                        case "total_hldr_eqy_exc_min_int" -> pojo.setTotalHldrEqyExcMinInt(parseDouble(value));
                        case "total_hldr_eqy_inc_min_int" -> pojo.setTotalHldrEqyIncMinInt(parseDouble(value));
                        case "minority_int" -> pojo.setMinorityInt(parseDouble(value));
                        case "update_flag" -> pojo.setUpdateFlag(value);
                        default -> {
                            if (!STOCK_BALANCESHEET_CORE_FIELDS.contains(field)) {
                                ext.put(field, value);
                            }
                        }
                    }
                }
                if (!ext.isEmpty()) {
                    pojo.setExtended(ext.toJSONString());
                }
                list.add(pojo);
            }
        } catch (Exception e) {
            log.error("Error converting StockBalancesheet data", e);
            return -1;
        }

        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
            StockBalancesheetDao dao = sqlSession.getMapper(StockBalancesheetDao.class);
            for (StockBalancesheet pojo : list) {
                dao.insertStockBalancesheet(pojo);
            }
            sqlSession.commit();
        } catch (Exception e) {
            log.error("Error storing StockBalancesheet data", e);
            return -2;
        }

        return list.size();
    }

    // 4.4 现金流量表
    private static final Set<String> STOCK_CASHFLOW_CORE_FIELDS = Set.of(
            "ts_code", "ann_date", "f_ann_date", "end_date", "comp_type", "report_type", "end_type",
            "c_fr_sale_sg", "n_cashflow_act", "n_cashflow_inv_act", "n_cash_flows_fnc_act",
            "free_cashflow", "c_cash_equ_end_period", "n_incr_cash_cash_equ", "update_flag"
    );

    public int storeStockCashflowByRawTuShareOutput(JSONArray data, JSONArray fields) {
        List<StockCashflow> list = new ArrayList<>();

        try {
            for (int i = 0; i < data.size(); i++) {
                JSONArray item = data.getJSONArray(i);
                StockCashflow pojo = new StockCashflow();
                JSONObject ext = new JSONObject();

                for (int j = 0; j < fields.size(); j++) {
                    String field = fields.getString(j);
                    String value = item.getString(j);

                    if (value == null || value.isEmpty()) continue;

                    switch (field) {
                        case "ts_code" -> pojo.setTsCode(value);
                        case "ann_date" -> pojo.setAnnDate(DateConvertUtils.convertDateStrToLong(value, "yyyyMMdd"));
                        case "f_ann_date" -> pojo.setFAnnDate(DateConvertUtils.convertDateStrToLong(value, "yyyyMMdd"));
                        case "end_date" -> pojo.setEndDate(DateConvertUtils.convertDateStrToLong(value, "yyyyMMdd"));
                        case "comp_type" -> pojo.setCompType(value);
                        case "report_type" -> pojo.setReportType(value);
                        case "end_type" -> pojo.setEndType(value);
                        case "c_fr_sale_sg" -> pojo.setCFrSaleSg(parseDouble(value));
                        case "n_cashflow_act" -> pojo.setNCashflowAct(parseDouble(value));
                        case "n_cashflow_inv_act" -> pojo.setNCashflowInvAct(parseDouble(value));
                        case "n_cash_flows_fnc_act" -> pojo.setNCashFlowsFncAct(parseDouble(value));
                        case "free_cashflow" -> pojo.setFreeCashflow(parseDouble(value));
                        case "c_cash_equ_end_period" -> pojo.setCCashEquEndPeriod(parseDouble(value));
                        case "n_incr_cash_cash_equ" -> pojo.setNIncrCashCashEqu(parseDouble(value));
                        case "update_flag" -> pojo.setUpdateFlag(value);
                        default -> {
                            if (!STOCK_CASHFLOW_CORE_FIELDS.contains(field)) {
                                ext.put(field, value);
                            }
                        }
                    }
                }
                if (!ext.isEmpty()) {
                    pojo.setExtended(ext.toJSONString());
                }
                list.add(pojo);
            }
        } catch (Exception e) {
            log.error("Error converting StockCashflow data", e);
            return -1;
        }

        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
            StockCashflowDao dao = sqlSession.getMapper(StockCashflowDao.class);
            for (StockCashflow pojo : list) {
                dao.insertStockCashflow(pojo);
            }
            sqlSession.commit();
        } catch (Exception e) {
            log.error("Error storing StockCashflow data", e);
            return -2;
        }

        return list.size();
    }

    // 4.5 业绩预告
    public int storeStockForecastByRawTuShareOutput(JSONArray data, JSONArray fields) {
        List<StockForecast> list = new ArrayList<>();

        try {
            for (int i = 0; i < data.size(); i++) {
                JSONArray item = data.getJSONArray(i);
                StockForecast pojo = new StockForecast();

                for (int j = 0; j < fields.size(); j++) {
                    String field = fields.getString(j);
                    String value = item.getString(j);

                    if (value == null || value.isEmpty()) continue;

                    switch (field) {
                        case "ts_code" -> pojo.setTsCode(value);
                        case "ann_date" -> pojo.setAnnDate(DateConvertUtils.convertDateStrToLong(value, "yyyyMMdd"));
                        case "end_date" -> pojo.setEndDate(DateConvertUtils.convertDateStrToLong(value, "yyyyMMdd"));
                        case "type" -> pojo.setType(value);
                        case "p_change_min" -> pojo.setPChangeMin(parseDouble(value));
                        case "p_change_max" -> pojo.setPChangeMax(parseDouble(value));
                        case "net_profit_min" -> pojo.setNetProfitMin(parseDouble(value));
                        case "net_profit_max" -> pojo.setNetProfitMax(parseDouble(value));
                        case "last_parent_net" -> pojo.setLastParentNet(parseDouble(value));
                        case "first_ann_date" -> pojo.setFirstAnnDate(DateConvertUtils.convertDateStrToLong(value, "yyyyMMdd"));
                        case "summary" -> pojo.setSummary(value);
                        case "change_reason" -> pojo.setChangeReason(value);
                    }
                }
                list.add(pojo);
            }
        } catch (Exception e) {
            log.error("Error converting StockForecast data", e);
            return -1;
        }

        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
            StockForecastDao dao = sqlSession.getMapper(StockForecastDao.class);
            for (StockForecast pojo : list) {
                dao.insertStockForecast(pojo);
            }
            sqlSession.commit();
        } catch (Exception e) {
            log.error("Error storing StockForecast data", e);
            return -2;
        }

        return list.size();
    }

    // 4.6 业绩快报
    public int storeStockExpressByRawTuShareOutput(JSONArray data, JSONArray fields) {
        List<StockExpress> list = new ArrayList<>();

        try {
            for (int i = 0; i < data.size(); i++) {
                JSONArray item = data.getJSONArray(i);
                StockExpress pojo = new StockExpress();

                for (int j = 0; j < fields.size(); j++) {
                    String field = fields.getString(j);
                    String value = item.getString(j);

                    if (value == null || value.isEmpty()) continue;

                    switch (field) {
                        case "ts_code" -> pojo.setTsCode(value);
                        case "ann_date" -> pojo.setAnnDate(DateConvertUtils.convertDateStrToLong(value, "yyyyMMdd"));
                        case "end_date" -> pojo.setEndDate(DateConvertUtils.convertDateStrToLong(value, "yyyyMMdd"));
                        case "revenue" -> pojo.setRevenue(parseDouble(value));
                        case "operate_profit" -> pojo.setOperateProfit(parseDouble(value));
                        case "total_profit" -> pojo.setTotalProfit(parseDouble(value));
                        case "n_income" -> pojo.setNIncome(parseDouble(value));
                        case "total_assets" -> pojo.setTotalAssets(parseDouble(value));
                        case "total_hldr_eqy_exc_min_int" -> pojo.setTotalHldrEqyExcMinInt(parseDouble(value));
                        case "diluted_eps" -> pojo.setDilutedEps(parseDouble(value));
                        case "diluted_roe" -> pojo.setDilutedRoe(parseDouble(value));
                        case "yoy_net_profit" -> pojo.setYoyNetProfit(parseDouble(value));
                        case "bps" -> pojo.setBps(parseDouble(value));
                        case "yoy_sales" -> pojo.setYoySales(parseDouble(value));
                        case "yoy_op" -> pojo.setYoyOp(parseDouble(value));
                        case "yoy_tp" -> pojo.setYoyTp(parseDouble(value));
                        case "yoy_dedu_np" -> pojo.setYoyDeduNp(parseDouble(value));
                        case "yoy_eps" -> pojo.setYoyEps(parseDouble(value));
                        case "yoy_roe" -> pojo.setYoyRoe(parseDouble(value));
                        case "perf_summary" -> pojo.setPerfSummary(value);
                        case "is_audit" -> pojo.setIsAudit(parseInt(value));
                        case "remark" -> pojo.setRemark(value);
                    }
                }
                list.add(pojo);
            }
        } catch (Exception e) {
            log.error("Error converting StockExpress data", e);
            return -1;
        }

        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
            StockExpressDao dao = sqlSession.getMapper(StockExpressDao.class);
            for (StockExpress pojo : list) {
                dao.insertStockExpress(pojo);
            }
            sqlSession.commit();
        } catch (Exception e) {
            log.error("Error storing StockExpress data", e);
            return -2;
        }

        return list.size();
    }

    // 4.7 卖方盈利预测
    public int storeStockReportRcByRawTuShareOutput(JSONArray data, JSONArray fields) {
        List<StockReportRc> list = new ArrayList<>();

        try {
            for (int i = 0; i < data.size(); i++) {
                JSONArray item = data.getJSONArray(i);
                StockReportRc pojo = new StockReportRc();

                for (int j = 0; j < fields.size(); j++) {
                    String field = fields.getString(j);
                    String value = item.getString(j);

                    if (value == null || value.isEmpty()) continue;

                    switch (field) {
                        case "ts_code" -> pojo.setTsCode(value);
                        case "name" -> pojo.setName(value);
                        case "report_date" -> pojo.setReportDate(DateConvertUtils.convertDateStrToLong(value, "yyyyMMdd"));
                        case "report_title" -> pojo.setReportTitle(value);
                        case "report_type" -> pojo.setReportType(value);
                        case "classify" -> pojo.setClassify(value);
                        case "org_name" -> pojo.setOrgName(value);
                        case "author_name" -> pojo.setAuthorName(value);
                        case "quarter" -> pojo.setQuarter(value);
                        case "op_rt" -> pojo.setOpRt(parseDouble(value));
                        case "op_pr" -> pojo.setOpPr(parseDouble(value));
                        case "tp" -> pojo.setTp(parseDouble(value));
                        case "np" -> pojo.setNp(parseDouble(value));
                        case "eps" -> pojo.setEps(parseDouble(value));
                        case "pe" -> pojo.setPe(parseDouble(value));
                        case "rd" -> pojo.setRd(parseDouble(value));
                        case "roe" -> pojo.setRoe(parseDouble(value));
                        case "ev_ebitda" -> pojo.setEvEbitda(parseDouble(value));
                        case "rating" -> pojo.setRating(value);
                        case "max_price" -> pojo.setMaxPrice(parseDouble(value));
                        case "min_price" -> pojo.setMinPrice(parseDouble(value));
                    }
                }
                list.add(pojo);
            }
        } catch (Exception e) {
            log.error("Error converting StockReportRc data", e);
            return -1;
        }

        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
            StockReportRcDao dao = sqlSession.getMapper(StockReportRcDao.class);
            for (StockReportRc pojo : list) {
                dao.insertStockReportRc(pojo);
            }
            sqlSession.commit();
        } catch (Exception e) {
            log.error("Error storing StockReportRc data", e);
            return -2;
        }

        return list.size();
    }

    // 4.8 个股资金流向
    public int storeStockMoneyflowByRawTuShareOutput(JSONArray data, JSONArray fields) {
        List<StockMoneyflow> list = new ArrayList<>();

        try {
            for (int i = 0; i < data.size(); i++) {
                JSONArray item = data.getJSONArray(i);
                StockMoneyflow pojo = new StockMoneyflow();

                for (int j = 0; j < fields.size(); j++) {
                    String field = fields.getString(j);
                    String value = item.getString(j);

                    if (value == null || value.isEmpty()) continue;

                    switch (field) {
                        case "ts_code" -> pojo.setTsCode(value);
                        case "trade_date" -> pojo.setTradeDate(DateConvertUtils.convertDateStrToLong(value, "yyyyMMdd"));
                        case "buy_sm_vol" -> pojo.setBuySmVol(parseLong(value));
                        case "buy_sm_amount" -> pojo.setBuySmAmount(parseDouble(value));
                        case "sell_sm_vol" -> pojo.setSellSmVol(parseLong(value));
                        case "sell_sm_amount" -> pojo.setSellSmAmount(parseDouble(value));
                        case "buy_md_vol" -> pojo.setBuyMdVol(parseLong(value));
                        case "buy_md_amount" -> pojo.setBuyMdAmount(parseDouble(value));
                        case "sell_md_vol" -> pojo.setSellMdVol(parseLong(value));
                        case "sell_md_amount" -> pojo.setSellMdAmount(parseDouble(value));
                        case "buy_lg_vol" -> pojo.setBuyLgVol(parseLong(value));
                        case "buy_lg_amount" -> pojo.setBuyLgAmount(parseDouble(value));
                        case "sell_lg_vol" -> pojo.setSellLgVol(parseLong(value));
                        case "sell_lg_amount" -> pojo.setSellLgAmount(parseDouble(value));
                        case "buy_elg_vol" -> pojo.setBuyElgVol(parseLong(value));
                        case "buy_elg_amount" -> pojo.setBuyElgAmount(parseDouble(value));
                        case "sell_elg_vol" -> pojo.setSellElgVol(parseLong(value));
                        case "sell_elg_amount" -> pojo.setSellElgAmount(parseDouble(value));
                        case "net_mf_vol" -> pojo.setNetMfVol(parseLong(value));
                        case "net_mf_amount" -> pojo.setNetMfAmount(parseDouble(value));
                    }
                }
                list.add(pojo);
            }
        } catch (Exception e) {
            log.error("Error converting StockMoneyflow data", e);
            return -1;
        }

        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
            StockMoneyflowDao dao = sqlSession.getMapper(StockMoneyflowDao.class);
            for (StockMoneyflow pojo : list) {
                dao.insertStockMoneyflow(pojo);
            }
            sqlSession.commit();
        } catch (Exception e) {
            log.error("Error storing StockMoneyflow data", e);
            return -2;
        }

        return list.size();
    }

    // 4.9 前十大股东
    public int storeStockTop10HoldersByRawTuShareOutput(JSONArray data, JSONArray fields) {
        List<StockTop10Holders> list = new ArrayList<>();

        try {
            for (int i = 0; i < data.size(); i++) {
                JSONArray item = data.getJSONArray(i);
                StockTop10Holders pojo = new StockTop10Holders();

                for (int j = 0; j < fields.size(); j++) {
                    String field = fields.getString(j);
                    String value = item.getString(j);

                    if (value == null || value.isEmpty()) continue;

                    switch (field) {
                        case "ts_code" -> pojo.setTsCode(value);
                        case "ann_date" -> pojo.setAnnDate(DateConvertUtils.convertDateStrToLong(value, "yyyyMMdd"));
                        case "end_date" -> pojo.setEndDate(DateConvertUtils.convertDateStrToLong(value, "yyyyMMdd"));
                        case "holder_name" -> pojo.setHolderName(value);
                        case "hold_amount" -> pojo.setHoldAmount(parseDouble(value));
                        case "hold_ratio" -> pojo.setHoldRatio(parseDouble(value));
                        case "hold_float_ratio" -> pojo.setHoldFloatRatio(parseDouble(value));
                        case "hold_change" -> pojo.setHoldChange(parseDouble(value));
                        case "holder_type" -> pojo.setHolderType(value);
                    }
                }
                list.add(pojo);
            }
        } catch (Exception e) {
            log.error("Error converting StockTop10Holders data", e);
            return -1;
        }

        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
            StockTop10HoldersDao dao = sqlSession.getMapper(StockTop10HoldersDao.class);
            for (StockTop10Holders pojo : list) {
                dao.insertStockTop10Holders(pojo);
            }
            sqlSession.commit();
        } catch (Exception e) {
            log.error("Error storing StockTop10Holders data", e);
            return -2;
        }

        return list.size();
    }

    // 4.10 限售股解禁
    public int storeStockShareFloatByRawTuShareOutput(JSONArray data, JSONArray fields) {
        List<StockShareFloat> list = new ArrayList<>();

        try {
            for (int i = 0; i < data.size(); i++) {
                JSONArray item = data.getJSONArray(i);
                StockShareFloat pojo = new StockShareFloat();

                for (int j = 0; j < fields.size(); j++) {
                    String field = fields.getString(j);
                    String value = item.getString(j);

                    if (value == null || value.isEmpty()) continue;

                    switch (field) {
                        case "ts_code" -> pojo.setTsCode(value);
                        case "ann_date" -> pojo.setAnnDate(DateConvertUtils.convertDateStrToLong(value, "yyyyMMdd"));
                        case "float_date" -> pojo.setFloatDate(DateConvertUtils.convertDateStrToLong(value, "yyyyMMdd"));
                        case "float_share" -> pojo.setFloatShare(parseDouble(value));
                        case "float_ratio" -> pojo.setFloatRatio(parseDouble(value));
                        case "holder_name" -> pojo.setHolderName(value);
                        case "share_type" -> pojo.setShareType(value);
                    }
                }
                list.add(pojo);
            }
        } catch (Exception e) {
            log.error("Error converting StockShareFloat data", e);
            return -1;
        }

        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
            StockShareFloatDao dao = sqlSession.getMapper(StockShareFloatDao.class);
            for (StockShareFloat pojo : list) {
                dao.insertStockShareFloat(pojo);
            }
            sqlSession.commit();
        } catch (Exception e) {
            log.error("Error storing StockShareFloat data", e);
            return -2;
        }

        return list.size();
    }

    // Helper methods
    private Double parseDouble(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return new BigDecimal(value).doubleValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long parseLong(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseInt(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
