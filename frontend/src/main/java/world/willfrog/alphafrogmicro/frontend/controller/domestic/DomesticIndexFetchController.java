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
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndex.*;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexFetchService;
import world.willfrog.alphafrogmicro.frontend.service.FetchTaskStatusService;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Controller
@RequestMapping("/fetch/domestic/index")
@Slf4j
@RequiredArgsConstructor
public class DomesticIndexFetchController {

    @DubboReference(timeout = 25000)
    private DomesticIndexFetchService domesticIndexFetchService;

    private final FetchTaskStatusService fetchTaskStatusService;

    @GetMapping("/info/market")
    public ResponseEntity<String> fetchDomesticIndexInfoByMarket(@RequestParam(name = "market") String market,
                                                                 @RequestParam(name = "offset") int offset,
                                                                 @RequestParam(name = "limit") int limit) {

        String taskUuid = UUID.randomUUID().toString();
        fetchTaskStatusService.registerTask(taskUuid, "index_info", 1);
        CompletableFuture<DomesticIndexInfoFetchByMarketResponse> futureResponse = domesticIndexFetchService.fetchDomesticIndexInfoByMarketAsync(
                DomesticIndexInfoFetchByMarketRequest.newBuilder()
                        .setMarket(market).setOffset(offset).setLimit(limit).build()
        );

        futureResponse.whenComplete((response, ex) -> {
            if (ex != null) {
                fetchTaskStatusService.markFailure(taskUuid, "index_info", 1, -1, ex.getMessage());
                log.error("Failed to fetch index info from market {}", market, ex);
                return;
            }
            int fetchedItemsCount = response.getFetchedItemsCount();
            if ("success".equalsIgnoreCase(response.getStatus())) {
                fetchTaskStatusService.markSuccess(taskUuid, "index_info", 1, fetchedItemsCount);
            } else {
                fetchTaskStatusService.markFailure(taskUuid, "index_info", 1, fetchedItemsCount, response.getStatus());
            }
            log.info("Fetched {} items from market {}", fetchedItemsCount, market);
        });

        JSONObject res = new JSONObject();
        res.put("message", "Task created. Please refer to the console for the result.");
        res.put("task_uuid", taskUuid);
        return ResponseEntity.ok(res.toString());
    }





}
