package world.willfrog.alphafrogmicro.domestic.fetch.rag;

import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.beans.factory.annotation.Autowired;
import world.willfrog.ragfetchapi.DubboRagFetchServiceTriple;
import world.willfrog.ragfetchapi.RagFetchRequest;
import world.willfrog.ragfetchapi.RagFetchResponse;

import java.util.concurrent.CompletableFuture;

/**
 * RAG 元数据抓取 Dubbo 服务实现。
 * 接收 frontend 通过 Dubbo RPC 发来的触发请求，异步执行公告/研报抓取任务。
 */
@DubboService
@Slf4j
public class RagFetchServiceImpl extends DubboRagFetchServiceTriple.RagFetchServiceImplBase {

    @Autowired
    private RagAnnouncementFetchJob annJob;

    @Autowired
    private RagResearchReportFetchJob reportJob;

    @Override
    public RagFetchResponse triggerRagFetch(RagFetchRequest req) {
        final String type = req.getType();
        final String startDate = req.getStartDate();
        final String endDate = req.getEndDate();

        if (type == null || type.isBlank() || startDate == null || startDate.isBlank()
                || endDate == null || endDate.isBlank()) {
            return RagFetchResponse.newBuilder()
                    .setStatus("error")
                    .setMessage("type, startDate, endDate 均不能为空")
                    .build();
        }

        CompletableFuture.runAsync(() -> {
            try {
                if ("announcement".equals(type) || "both".equals(type)) {
                    log.info("[RagFetchService] 触发公告抓取 {} ~ {}", startDate, endDate);
                    annJob.fetchRange(startDate, endDate);
                }
                if ("research_report".equals(type) || "both".equals(type)) {
                    log.info("[RagFetchService] 触发研报抓取 {} ~ {} industries={}",
                            startDate, endDate, req.getTargetIndustriesList());
                    reportJob.fetchRange(startDate, endDate, req.getTargetIndustriesList());
                }
                log.info("[RagFetchService] 任务完成: type={} {} ~ {}", type, startDate, endDate);
            } catch (Exception e) {
                log.error("[RagFetchService] 任务失败: type={} {} ~ {}: {}",
                        type, startDate, endDate, e.getMessage(), e);
            }
        });

        return RagFetchResponse.newBuilder()
                .setStatus("triggered")
                .setMessage("type=" + type + " " + startDate + "~" + endDate)
                .build();
    }
}
