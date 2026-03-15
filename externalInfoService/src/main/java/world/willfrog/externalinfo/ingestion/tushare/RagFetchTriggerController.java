package world.willfrog.externalinfo.ingestion.tushare;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * RAG 元数据抓取手动触发端点（内网侧，仅供 frontend 转发）。
 *
 * <p>绕过 enabled 开关，直接调用 fetchRange()，适合测试和历史数据补录。
 * tushareToken 仍需在 rag-fetch.local.json 中配置，否则返回 400。
 * 任务异步执行，接口立即返回 triggered。
 */
@RestController
@RequestMapping("/rag")
@Slf4j
public class RagFetchTriggerController {

    private final AnnouncementFetchJob announcementFetchJob;
    private final ResearchReportFetchJob researchReportFetchJob;
    private final RagFetchLocalConfigLoader configLoader;

    public RagFetchTriggerController(AnnouncementFetchJob announcementFetchJob,
                                     ResearchReportFetchJob researchReportFetchJob,
                                     RagFetchLocalConfigLoader configLoader) {
        this.announcementFetchJob = announcementFetchJob;
        this.researchReportFetchJob = researchReportFetchJob;
        this.configLoader = configLoader;
    }

    @PostMapping("/fetch/trigger")
    public ResponseEntity<?> trigger(@RequestBody RagFetchTriggerRequest request) {
        String type = request.getType();
        String startDate = request.getStartDate();
        String endDate = request.getEndDate();

        if (type == null || type.isBlank() || startDate == null || endDate == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "type, startDate, endDate are required"));
        }
        if (!type.equals("announcement") && !type.equals("research_report") && !type.equals("both")) {
            return ResponseEntity.badRequest().body(Map.of("error", "type must be announcement, research_report, or both"));
        }

        boolean tokenOk = configLoader.current()
                .map(c -> !c.getTushareToken().isBlank())
                .orElse(false);
        if (!tokenOk) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "tushareToken not configured in rag-fetch.local.json"));
        }

        // 确定研报行业列表
        List<String> industries = null;
        if (type.equals("research_report") || type.equals("both")) {
            if (request.getTargetIndustries() != null) {
                // 请求里显式传了（含空列表：传空 = 全量不过滤）
                industries = request.getTargetIndustries();
            } else {
                // 未传：回退到 config 里的 targetIndustries
                industries = configLoader.current()
                        .map(c -> c.getResearchReport().getTargetIndustries())
                        .orElse(List.of());
            }
        }

        final List<String> finalIndustries = industries;
        final String finalType = type;

        CompletableFuture.runAsync(() -> {
            try {
                if (finalType.equals("announcement") || finalType.equals("both")) {
                    log.info("[FetchTrigger] announcement {} ~ {}", startDate, endDate);
                    announcementFetchJob.fetchRange(startDate, endDate);
                }
                if (finalType.equals("research_report") || finalType.equals("both")) {
                    if (finalIndustries == null || finalIndustries.isEmpty()) {
                        log.info("[FetchTrigger] research_report {} ~ {} all industries", startDate, endDate);
                        researchReportFetchJob.fetchRange(startDate, endDate, null);
                    } else {
                        for (String ind : finalIndustries) {
                            log.info("[FetchTrigger] research_report {} ~ {} ind={}", startDate, endDate, ind);
                            researchReportFetchJob.fetchRange(startDate, endDate, ind);
                        }
                    }
                }
                log.info("[FetchTrigger] done: type={} {} ~ {}", finalType, startDate, endDate);
            } catch (Exception e) {
                log.error("[FetchTrigger] failed: type={} {} ~ {}: {}", finalType, startDate, endDate, e.getMessage(), e);
            }
        });

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "triggered");
        resp.put("type", type);
        resp.put("startDate", startDate);
        resp.put("endDate", endDate);
        if (industries != null) {
            resp.put("targetIndustries", industries.isEmpty() ? "all" : industries);
        }
        return ResponseEntity.ok(resp);
    }
}
