package world.willfrog.alphafrogmicro.frontend.controller.domestic;

import com.google.protobuf.util.JsonFormat;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStock.*;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockService;

@Controller
@RequestMapping("/domestic/stock")
@Slf4j
public class DomesticStockController {

    @DubboReference(timeout = 35000)
    private DomesticStockService domesticStockService;

    @GetMapping("/info/ts_code")
    public ResponseEntity<String> getStockInfoByTsCode(@RequestParam(name = "ts_code") String tsCode) {
        try {
            DomesticStockInfoByTsCodeRequest request = DomesticStockInfoByTsCodeRequest.newBuilder().setTsCode(tsCode).build();

            DomesticStockInfoByTsCodeResponse response = domesticStockService.getStockInfoByTsCode(request);

            DomesticStockInfoFullItem item = response.getItem();

            String jsonResponse = JsonFormat.printer()
                    .preservingProtoFieldNames()
                    .omittingInsignificantWhitespace()
                    .includingDefaultValueFields()
                    .print(item);

            return ResponseEntity.ok(jsonResponse);
        } catch (Exception e) {
            log.error("Error occurred while getting stock info by ts code: {}", e.getMessage());
            return ResponseEntity.status(500).body("Error occurred while getting stock info by ts code");
        }
    }

    @GetMapping("/search")
    public ResponseEntity<String> searchStock(@RequestParam("query") String query) {
        try {
            DomesticStockSearchRequest request = DomesticStockSearchRequest.newBuilder().setQuery(query).build();

            DomesticStockSearchResponse response = domesticStockService.searchStock(request);

            String jsonResponse = JsonFormat.printer()
                    .preservingProtoFieldNames()
                    .omittingInsignificantWhitespace()
                    .includingDefaultValueFields()
                    .print(response);

            return ResponseEntity.ok(jsonResponse);
        } catch (Exception e) {
            log.error("Error occurred while searching stock: {}", e.getMessage());
            return ResponseEntity.status(500).body("Error occurred while searching stock info");
        }
    }

    @GetMapping("/search_advanced")
    public ResponseEntity<String> searchStockES(@RequestParam("query") String query) {
        try {
            DomesticStockSearchESRequest request = DomesticStockSearchESRequest.newBuilder().setQuery(query).build();

            DomesticStockSearchESResponse response = domesticStockService.searchStockES(request);

            String jsonResponse = JsonFormat.printer()
                    .preservingProtoFieldNames()
                    .omittingInsignificantWhitespace()
                    .includingDefaultValueFields()
                    .print(response);

            return ResponseEntity.ok(jsonResponse);
        } catch (Exception e) {
            log.error("Error occurred while searching stock with Elasticsearch: {}", e.getMessage());
            return ResponseEntity.status(500).body("Error occurred while searching stock info with Elasticsearch");
        }
    }

    @GetMapping("/daily/ts_code")
    public ResponseEntity<String> getStockDailyByTsCode(@RequestParam("ts_code") String tsCode,
                                                        @RequestParam("start_date_timestamp") long startDate,
                                                        @RequestParam("end_date_timestamp") long endDate) {
        try {
            DomesticStockDailyByTsCodeAndDateRangeRequest request =
                    DomesticStockDailyByTsCodeAndDateRangeRequest.newBuilder().setTsCode(tsCode)
                            .setStartDate(startDate).setEndDate(endDate).build();
            DomesticStockDailyByTsCodeAndDateRangeResponse response = domesticStockService.getStockDailyByTsCodeAndDateRange(request);
            String jsonResponse = JsonFormat.printer()
                    .preservingProtoFieldNames()
                    .omittingInsignificantWhitespace()
                    .includingDefaultValueFields()
                    .print(response);
            return ResponseEntity.ok(jsonResponse);
        } catch (Exception e) {
            log.error("Error occurred while getting stock daily by ts code: {}", e.getMessage());
            return ResponseEntity.status(500).body("Error occurred while getting stock daily by ts code");
        }
    }
}
