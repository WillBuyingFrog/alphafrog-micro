package world.willfrog.alphafrogmicro.frontend.controller.domestic;

import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndex;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticTradeCalendarFetchService;

import java.util.concurrent.CompletableFuture;

@Controller
@RequestMapping("/fetch/domestic/trade-calendar")
@Slf4j
public class DomesticTradeCalendarFetchController {

    @DubboReference
    private DomesticTradeCalendarFetchService domesticTradeCalendarFetchService;

    @GetMapping("/v1")
    public ResponseEntity<String> fetchDomesticTradeCalendar(@RequestParam(name = "start_date") long startDateTimestamp,
                                                             @RequestParam(name = "end_date") long endDateTimestamp) {
        CompletableFuture<DomesticIndex.DomesticTradeCalendarFetchByDateRangeResponse> responseCompletableFuture =
                domesticTradeCalendarFetchService.fetchDomesticTradeCalendarByDateRangeAsync(
                        DomesticIndex.DomesticTradeCalendarFetchByDateRangeRequest.newBuilder()
                                .setStartDate(startDateTimestamp)
                                .setEndDate(endDateTimestamp)
                                .build()
                );

        responseCompletableFuture.thenAccept(response -> {
            log.info("Fetched {} items from {} to {}", response.getFetchedItemsCount(),
                    startDateTimestamp, endDateTimestamp);
        });

        return ResponseEntity.ok("Task created. Please refer to the console for the result.");
    }

}
