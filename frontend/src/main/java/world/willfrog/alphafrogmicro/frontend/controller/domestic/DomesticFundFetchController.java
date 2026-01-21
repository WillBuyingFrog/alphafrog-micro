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
import world.willfrog.alphafrogmicro.domestic.idl.*;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticFundFetchService;
import world.willfrog.alphafrogmicro.frontend.service.FetchTaskStatusService;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Controller
@RequestMapping("/fetch/domestic/fund")
@Slf4j
@RequiredArgsConstructor
public class DomesticFundFetchController {

    @DubboReference(timeout = 25000)
    private DomesticFundFetchService domesticFundFetchService;

    private final FetchTaskStatusService fetchTaskStatusService;

    @GetMapping("/info/market")
    public ResponseEntity<String> fetchDomesticFundInfoByMarket(@RequestParam(name = "market") String market,
                                                        @RequestParam(name = "offset") int offset,
                                                        @RequestParam(name = "limit") int limit) {

        String taskUuid = UUID.randomUUID().toString();
        fetchTaskStatusService.registerTask(taskUuid, "fund_info", 1);

        CompletableFuture<DomesticFundInfoFetchByMarketResponse> futureResponse = domesticFundFetchService.fetchDomesticFundInfoByMarketAsync(
                DomesticFundInfoFetchByMarketRequest.newBuilder()
                        .setMarket(market).setOffset(offset).setLimit(limit).build()
        );

        futureResponse.whenComplete((response, ex) -> {
            if (ex != null) {
                fetchTaskStatusService.markFailure(taskUuid, "fund_info", 1, -1, ex.getMessage());
                log.error("Failed to fetch fund info from market {}", market, ex);
                return;
            }
            int fetchedItemsCount = response.getFetchedItemsCount();
            if ("success".equalsIgnoreCase(response.getStatus())) {
                fetchTaskStatusService.markSuccess(taskUuid, "fund_info", 1, fetchedItemsCount);
            } else {
                fetchTaskStatusService.markFailure(taskUuid, "fund_info", 1, fetchedItemsCount, response.getStatus());
            }
            log.info("Fetched {} items from market {}", fetchedItemsCount, market);
        });

        JSONObject res = new JSONObject();
        res.put("message", "Task created. Please refer to the console for the result.");
        res.put("task_uuid", taskUuid);
        return ResponseEntity.ok(res.toString());
    }

    @GetMapping("/nav/trade_date")
    public ResponseEntity<String> fetchDomesticFundNavByTradeDate(@RequestParam(name = "trade_date_timestamp") long tradeDateTimestamp,
                                                          @RequestParam(name = "offset") int offset,
                                                          @RequestParam(name = "limit") int limit) {
        String taskUuid = UUID.randomUUID().toString();
        fetchTaskStatusService.registerTask(taskUuid, "fund_nav", 1);

        CompletableFuture<DomesticFundNavFetchByTradeDateResponse> futureResponse = domesticFundFetchService.fetchDomesticFundNavByTradeDateAsync(
                DomesticFundNavFetchByTradeDateRequest.newBuilder()
                        .setTradeDateTimestamp(tradeDateTimestamp).setOffset(offset).setLimit(limit).build()
        );

        futureResponse.whenComplete((response, ex) -> {
            if (ex != null) {
                fetchTaskStatusService.markFailure(taskUuid, "fund_nav", 1, -1, ex.getMessage());
                log.error("Failed to fetch fund nav for trade date {}", tradeDateTimestamp, ex);
                return;
            }
            int fetchedItemsCount = response.getFetchedItemsCount();
            if ("success".equalsIgnoreCase(response.getStatus())) {
                fetchTaskStatusService.markSuccess(taskUuid, "fund_nav", 1, fetchedItemsCount);
            } else {
                fetchTaskStatusService.markFailure(taskUuid, "fund_nav", 1, fetchedItemsCount, response.getStatus());
            }
            log.info("Fetched {} items from trade date {}", fetchedItemsCount, tradeDateTimestamp);
        });

        JSONObject res = new JSONObject();
        res.put("message", "Task created. Please refer to the console for the result.");
        res.put("task_uuid", taskUuid);
        return ResponseEntity.ok(res.toString());
    }

}
