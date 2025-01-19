package world.willfrog.alphafrogmicro.frontend.controller.domestic;

import lombok.extern.slf4j.Slf4j;
import org.apache.coyote.Response;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStock;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockFetchService;

import java.util.concurrent.CompletableFuture;

@Controller
@RequestMapping("/fetch/domestic/stock")
@Slf4j
public class DomesticStockFetchController {

    @DubboReference(timeout = 35000)
    private DomesticStockFetchService domesticStockFetchService;

    @GetMapping("/info/market")
    public ResponseEntity<String> fetchDomesticStockInfoByMarket(@RequestParam(name = "market") String market,
                                                                 @RequestParam(name = "offset") int offset,
                                                                 @RequestParam(name = "limit") int limit) {
        CompletableFuture<DomesticStock.DomesticStockInfoFetchByMarketResponse> futureResponse = domesticStockFetchService.fetchStockInfoByMarketAsync(
                DomesticStock.DomesticStockInfoFetchByMarketRequest.newBuilder()
                        .setMarket(market).setOffset(offset).setLimit(limit).build()
        );

        futureResponse.thenApply(response -> {
            int fetchedItemsCount = response.getFetchedItemsCount();
            log.info("Fetched {} items from market {}", fetchedItemsCount, market);
            return null;
        });

        return ResponseEntity.ok("Task created. Please refer to the console for the result.");
    }


    @GetMapping("/daily/trade_date")
    public ResponseEntity<String> fetchDomesticStockDailyByTradeDate(@RequestParam(name = "trade_date_timestamp") long tradeDateTimestamp,
                                                           @RequestParam(name = "offset") int offset,
                                                           @RequestParam(name = "limit") int limit) {
        CompletableFuture<DomesticStock.DomesticStockDailyFetchByTradeDateResponse> futureResponse = domesticStockFetchService.fetchStockDailyByTradeDateAsync(
                DomesticStock.DomesticStockDailyFetchByTradeDateRequest.newBuilder()
                        .setTradeDate(tradeDateTimestamp).setOffset(offset).setLimit(limit).build()
        );

        futureResponse.thenApply(response -> {
            int fetchedItemsCount = response.getFetchedItemsCount();
            log.info("Fetched {} items from trade date {}", fetchedItemsCount, tradeDateTimestamp);
            return null;
        });

        return ResponseEntity.ok("Task created. Please refer to the console for the result.");
    }
}
