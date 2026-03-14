package world.willfrog.externalinfo.ingestion.tushare;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时拉取券商研究报告元数据（TuShare research_report 接口）并存入 DB。
 * 按主题（ind_name）循环拉取，targetIndustries 从 configLoader 读取，支持热更新。
 */
@Component
@Slf4j
public class ResearchReportFetchJob {

    private final RagFetchLocalConfigLoader configLoader;
    private final TuShareRagApiClient apiClient;
    private final RagResearchReportDao researchReportDao;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String API_NAME = "research_report";
    private static final String FIELDS = "trade_date,title,abstr,report_type,author,name,ts_code,inst_csname,ind_name,url";
    private static final int PAGE_LIMIT = 1000;

    public ResearchReportFetchJob(RagFetchLocalConfigLoader configLoader,
                                  TuShareRagApiClient apiClient,
                                  RagResearchReportDao researchReportDao) {
        this.configLoader = configLoader;
        this.apiClient = apiClient;
        this.researchReportDao = researchReportDao;
    }

    /**
     * 每天早 6:30 增量更新（拉昨日研报）。
     */
    @Scheduled(cron = "0 30 6 * * *")
    public void incrementalFetch() {
        if (configLoader.current().map(c -> !c.isEnabled()).orElse(true)) {
            return;
        }
        String yesterday = LocalDate.now().minusDays(1).format(DATE_FMT);
        log.info("[ResearchReportFetchJob] incremental fetch for date={}", yesterday);

        List<String> industries = configLoader.current()
                .map(c -> c.getResearchReport().getTargetIndustries())
                .orElse(List.of());

        if (industries.isEmpty()) {
            fetchRange(yesterday, yesterday, null);
        } else {
            for (String indName : industries) {
                fetchRange(yesterday, yesterday, indName);
            }
        }
    }

    /**
     * 按日期范围和行业拉取研报（供初始化历史数据或手动触发使用）。
     * 按月分段调用以避免单次数据量过大。
     *
     * @param startDate 开始日期 YYYYMMDD
     * @param endDate   结束日期 YYYYMMDD
     * @param indName   行业名称，null 表示全量
     */
    public void fetchRange(String startDate, String endDate, String indName) {
        LocalDate start = LocalDate.parse(startDate, DATE_FMT);
        LocalDate end = LocalDate.parse(endDate, DATE_FMT);

        // 按月分段
        LocalDate segStart = start;
        while (!segStart.isAfter(end)) {
            LocalDate segEnd = segStart.withDayOfMonth(
                    Math.min(segStart.lengthOfMonth(), segStart.getDayOfMonth() + 30));
            if (segEnd.isAfter(end)) {
                segEnd = end;
            }
            // 确保 segEnd 不超过当月最后一天
            if (segEnd.getMonthValue() != segStart.getMonthValue()) {
                segEnd = segStart.withDayOfMonth(segStart.lengthOfMonth());
            }

            try {
                fetchSegment(segStart.format(DATE_FMT), segEnd.format(DATE_FMT), indName);
            } catch (Exception e) {
                log.error("[ResearchReportFetchJob] failed to fetch segment {}-{} ind={}: {}",
                        segStart.format(DATE_FMT), segEnd.format(DATE_FMT), indName, e.getMessage(), e);
            }

            segStart = segEnd.plusDays(1);
        }
    }

    private void fetchSegment(String startDate, String endDate, String indName) {
        int offset = 0;
        int totalInserted = 0;

        while (true) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("api_name", API_NAME);

            Map<String, Object> apiParams = new LinkedHashMap<>();
            apiParams.put("start_date", startDate);
            apiParams.put("end_date", endDate);
            if (indName != null && !indName.isBlank()) {
                apiParams.put("ind_name", indName);
            }
            apiParams.put("offset", offset);
            apiParams.put("limit", PAGE_LIMIT);
            params.put("params", apiParams);
            params.put("fields", FIELDS);

            JSONObject resp = apiClient.post(params);
            if (resp == null) {
                log.warn("[ResearchReportFetchJob] null response for {}-{} ind={} offset={}",
                        startDate, endDate, indName, offset);
                break;
            }

            Integer code = resp.getInteger("code");
            if (code == null || code != 0) {
                log.warn("[ResearchReportFetchJob] API error for {}-{} ind={}: code={}, msg={}",
                        startDate, endDate, indName, code, resp.getString("msg"));
                break;
            }

            JSONObject data = resp.getJSONObject("data");
            if (data == null) {
                break;
            }
            JSONArray items = data.getJSONArray("items");
            if (items == null || items.isEmpty()) {
                break;
            }

            List<List<String>> records = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                JSONArray row = items.getJSONArray(i);
                if (row == null) continue;
                List<String> record = new ArrayList<>();
                for (int j = 0; j < row.size(); j++) {
                    Object val = row.get(j);
                    record.add(val == null ? null : val.toString());
                }
                records.add(record);
            }

            int inserted = researchReportDao.batchUpsert(records);
            totalInserted += inserted;
            log.info("[ResearchReportFetchJob] {}-{} ind={} offset={} fetched={} inserted={}",
                    startDate, endDate, indName, offset, items.size(), inserted);

            if (items.size() < PAGE_LIMIT) {
                break;
            }
            offset += items.size();
        }

        log.info("[ResearchReportFetchJob] {}-{} ind={} totalInserted={}",
                startDate, endDate, indName, totalInserted);
    }
}
