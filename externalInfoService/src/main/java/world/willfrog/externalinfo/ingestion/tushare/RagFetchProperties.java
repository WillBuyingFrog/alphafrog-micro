package world.willfrog.externalinfo.ingestion.tushare;

import lombok.Data;

import java.util.List;

@Data
public class RagFetchProperties {
    private boolean enabled = false;
    private String tushareToken = "";
    private Announcement announcement = new Announcement();
    private ResearchReport researchReport = new ResearchReport();

    @Data
    public static class Announcement {
        private String startDate = "20190101";
    }

    @Data
    public static class ResearchReport {
        private String startDate = "20190101";
        private List<String> targetIndustries = List.of();
    }
}
