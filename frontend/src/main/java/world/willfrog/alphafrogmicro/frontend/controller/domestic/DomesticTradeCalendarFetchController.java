package world.willfrog.alphafrogmicro.frontend.controller.domestic;

import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndex;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticTradeCalendarFetchService;
import world.willfrog.alphafrogmicro.frontend.service.FetchTaskStatusService;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Controller
@RequestMapping("/fetch/domestic/trade-calendar")
@Slf4j
@RequiredArgsConstructor
public class DomesticTradeCalendarFetchController {

    @DubboReference
    private DomesticTradeCalendarFetchService domesticTradeCalendarFetchService;

    private final FetchTaskStatusService fetchTaskStatusService;

    @GetMapping("/v1")
    public ResponseEntity<String> fetchDomesticTradeCalendar(@RequestParam(name = "start_date") long startDateTimestamp,
                                                             @RequestParam(name = "end_date") long endDateTimestamp) {
        String taskUuid = UUID.randomUUID().toString();
        fetchTaskStatusService.registerTask(taskUuid, "trade_calendar", 1);
        CompletableFuture<DomesticIndex.DomesticTradeCalendarFetchByDateRangeResponse> responseCompletableFuture =
                domesticTradeCalendarFetchService.fetchDomesticTradeCalendarByDateRangeAsync(
                        DomesticIndex.DomesticTradeCalendarFetchByDateRangeRequest.newBuilder()
                                .setStartDate(startDateTimestamp)
                                .setEndDate(endDateTimestamp)
                                .build()
                );

        responseCompletableFuture.whenComplete((response, ex) -> {
            if (ex != null) {
                fetchTaskStatusService.markFailure(taskUuid, "trade_calendar", 1, -1, ex.getMessage());
                log.error("Failed to fetch trade calendar from {} to {}", startDateTimestamp, endDateTimestamp, ex);
                return;
            }
            int fetchedItemsCount = response.getFetchedItemsCount();
            if ("success".equalsIgnoreCase(response.getStatus())) {
                fetchTaskStatusService.markSuccess(taskUuid, "trade_calendar", 1, fetchedItemsCount);
            } else {
                fetchTaskStatusService.markFailure(taskUuid, "trade_calendar", 1, fetchedItemsCount, response.getStatus());
            }
            log.info("Fetched {} items from {} to {}", response.getFetchedItemsCount(),
                    startDateTimestamp, endDateTimestamp);
        });

        JSONObject res = new JSONObject();
        res.put("message", "Task created. Please refer to the console for the result.");
        res.put("task_uuid", taskUuid);
        return ResponseEntity.ok(res.toString());
    }

}
