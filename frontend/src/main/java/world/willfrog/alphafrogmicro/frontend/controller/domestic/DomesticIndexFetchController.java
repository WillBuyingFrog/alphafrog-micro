package world.willfrog.alphafrogmicro.frontend.controller.domestic;


import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndex.*;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexFetchService;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Controller
@RequestMapping("/fetch/domestic/index")
@Slf4j
public class DomesticIndexFetchController {

    @DubboReference(timeout = 25000)
    private DomesticIndexFetchService domesticIndexFetchService;

    @GetMapping("/info/market")
    public ResponseEntity<String> fetchDomesticIndexInfoByMarket(@RequestParam(name = "market") String market,
                                                                 @RequestParam(name = "offset") int offset,
                                                                 @RequestParam(name = "limit") int limit) {


        CompletableFuture<DomesticIndexInfoFetchByMarketResponse> futureResponse = domesticIndexFetchService.fetchDomesticIndexInfoByMarketAsync(
                DomesticIndexInfoFetchByMarketRequest.newBuilder()
                        .setMarket(market).setOffset(offset).setLimit(limit).build()
        );

        futureResponse.thenApply(response -> {
            int fetchedItemsCount = response.getFetchedItemsCount();
            log.info("Fetched {} items from market {}", fetchedItemsCount, market);
            return null;
        });

        return ResponseEntity.ok("Task created. Please refer to the console for the result.");
    }





}
