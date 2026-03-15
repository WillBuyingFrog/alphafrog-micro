package world.willfrog.externalinfo.ingestion.tushare;

import lombok.Data;

import java.util.List;

@Data
public class RagFetchTriggerRequest {

    /** "announcement" | "research_report" | "both" */
    private String type;

    /** 开始日期，YYYYMMDD */
    private String startDate;

    /** 结束日期，YYYYMMDD */
    private String endDate;

    /**
     * 研报行业列表（仅 type=research_report/both 时生效）。
     * 不填则使用 rag-fetch.local.json 里的 targetIndustries；
     * 传空列表则拉全量行业（不过滤）。
     */
    private List<String> targetIndustries;
}
