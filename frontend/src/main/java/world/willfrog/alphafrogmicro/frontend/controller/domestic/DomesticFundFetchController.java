package world.willfrog.alphafrogmicro.frontend.controller.domestic;

import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticFund;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticFundFetchService;

import java.util.concurrent.CompletableFuture;

@Controller
@RequestMapping("/fetch/domestic/fund")
@Slf4j
public class DomesticFundFetchController {

    @DubboReference(timeout = 25000)
    private DomesticFundFetchService domesticFundFetchService;

    @GetMapping("/info/market")
    public ResponseEntity<String> fetchDomesticFundInfoByMarket(@RequestParam(name = "market") String market,
                                                        @RequestParam(name = "offset") int offset,
                                                        @RequestParam(name = "limit") int limit) {

        CompletableFuture<DomesticFund.DomesticFundInfoFetchByMarketResponse> futureResponse = domesticFundFetchService.fetchDomesticFundInfoByMarketAsync(
                DomesticFund.DomesticFundInfoFetchByMarketRequest.newBuilder()
                        .setMarket(market).setOffset(offset).setLimit(limit).build()
        );

        futureResponse.thenApply(response -> {
            int fetchedItemsCount = response.getFetchedItemsCount();
            log.info("Fetched {} items from market {}", fetchedItemsCount, market);
            return null;
        });

        return ResponseEntity.ok("Task created. Please refer to the console for the result.");
    }

    @GetMapping("/nav/trade_date")
    public ResponseEntity<String> fetchDomesticFundNavByTradeDate(@RequestParam(name = "trade_date_timestamp") long tradeDateTimestamp,
                                                          @RequestParam(name = "offset") int offset,
                                                          @RequestParam(name = "limit") int limit) {
        CompletableFuture<DomesticFund.DomesticFundNavFetchByTradeDateResponse> futureResponse = domesticFundFetchService.fetchDomesticFundNavByTradeDateAsync(
                DomesticFund.DomesticFundNavFetchByTradeDateRequest.newBuilder()
                        .setTradeDateTimestamp(tradeDateTimestamp).setOffset(offset).setLimit(limit).build()
        );

        futureResponse.thenApply(response -> {
            int fetchedItemsCount = response.getFetchedItemsCount();
            log.info("Fetched {} items from trade date {}", fetchedItemsCount, tradeDateTimestamp);
            return null;
        });

        return ResponseEntity.ok("Task created. Please refer to the console for the result.");
    }


    @GetMapping("/portfolio/date_range")
    public ResponseEntity<String> fetchDomesticFundPortfolioByDateRange(@RequestParam(name = "start_Date_timestamp") long startDateTimestamp,
                                                                        @RequestParam(name = "end_date_timestamp") long endDateTimestamp,
                                                               @RequestParam(name = "offset") int offset,
                                                               @RequestParam(name = "limit") int limit) {
        CompletableFuture<DomesticFund.DomesticFundPortfolioFetchByDateRangeResponse> futureResponse = domesticFundFetchService.fetchDomesticFundPortfolioByDateRangeAsync(
                DomesticFund.DomesticFundPortfolioFetchByDateRangeRequest.newBuilder()
                        .setStartDateTimestamp(startDateTimestamp).setEndDateTimestamp(endDateTimestamp)
                        .setOffset(offset).setLimit(limit).build()
        );

        futureResponse.thenApply(response -> {
            int fetchedItemsCount = response.getFetchedItemsCount();
            log.info("Fetched {} items from date range {} to {}", fetchedItemsCount, startDateTimestamp, endDateTimestamp);
            return null;
        });

        return ResponseEntity.ok("Task created. Please refer to the console for the result.");
    }

}
