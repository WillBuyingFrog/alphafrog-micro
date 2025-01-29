package world.willfrog.alphafrogmicro.frontend.controller.domestic;

import com.google.protobuf.util.JsonFormat;
import lombok.extern.slf4j.Slf4j;
import org.apache.coyote.Response;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticFund;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticFundService;

import java.io.IOException;

@Controller
@RequestMapping("/domestic/fund")
@Slf4j
public class DomesticFundController {

    @DubboReference
    private DomesticFundService domesticFundService;

    @GetMapping("/info/tsCode")
    public ResponseEntity<String> getFundInfoByTsCode(@RequestParam(name = "ts_code") String tsCode) {
        DomesticFund.DomesticFundInfoByTsCodeResponse response = domesticFundService.getDomesticFundInfoByTsCode(
                DomesticFund.DomesticFundInfoByTsCodeRequest.newBuilder().setTsCode(tsCode).build()
        );

        // JSON序列化
        String jsonResponse;
        try {
            jsonResponse = JsonFormat.printer()
                    .preservingProtoFieldNames()
                    .omittingInsignificantWhitespace()
                    .includingDefaultValueFields()
                    .print(response);
        } catch (IOException e) {
            log.error("Error converting response to JSON: ", e);
            return ResponseEntity.status(500).body("Internal Server Error");
        }

        return ResponseEntity.ok(jsonResponse);
    }

    @GetMapping("/search")
    public ResponseEntity<String> searchFundInfo(@RequestParam(name = "query") String query) {
        DomesticFund.DomesticFundSearchResponse response = domesticFundService.searchDomesticFundInfo(
                DomesticFund.DomesticFundSearchRequest.newBuilder().setQuery(query).build()
        );

        // JSON序列化
        String jsonResponse;
        try {
            jsonResponse = JsonFormat.printer()
                    .preservingProtoFieldNames()
                    .omittingInsignificantWhitespace()
                    .includingDefaultValueFields()
                    .print(response);
        } catch (IOException e) {
            log.error("Error converting response to JSON: ", e);
            return ResponseEntity.status(500).body("Internal Server Error");
        }

        return ResponseEntity.ok(jsonResponse);
    }


    @GetMapping("/nav/ts_code")
    public ResponseEntity<String> getFundNavByTsCodeAndDateRange(@RequestParam(name = "ts_code") String tsCode,
                                                                   @RequestParam(name = "start_date_timestamp") long startDateTimestamp,
                                                                   @RequestParam(name = "end_date_timestamp") long endDateTimestamp) {
        DomesticFund.DomesticFundNavsByTsCodeAndDateRangeResponse response = domesticFundService.getDomesticFundNavsByTsCodeAndDateRange(
                DomesticFund.DomesticFundNavsByTsCodeAndDateRangeRequest.newBuilder()
                        .setTsCode(tsCode)
                        .setStartDateTimestamp(startDateTimestamp)
                        .setEndDateTimestamp(endDateTimestamp)
                        .build()
        );

        try {
            String jsonResponse = JsonFormat.printer()
                    .preservingProtoFieldNames()
                    .omittingInsignificantWhitespace()
                    .includingDefaultValueFields()
                    .print(response);
            return ResponseEntity.ok(jsonResponse);
        } catch (IOException e) {
            log.error("Error converting response to JSON: ", e);
            return ResponseEntity.status(500).body("Internal Server Error");
        }
    }

    @GetMapping("/portfolio/ts_code")
    public ResponseEntity<String> getFundPortfolioByTsCodeAndDateRange(@RequestParam(name = "ts_code") String tsCode,
                                                                       @RequestParam(name = "start_date_timestamp") long startDateTimestamp,
                                                                       @RequestParam(name = "end_date_timestamp") long endDateTimestamp) {
        DomesticFund.DomesticFundPortfolioByTsCodeAndDateRangeResponse response = domesticFundService.getDomesticFundPortfolioByTsCodeAndDateRange(
                DomesticFund.DomesticFundPortfolioByTsCodeAndDateRangeRequest.newBuilder()
                        .setTsCode(tsCode)
                        .setStartDateTimestamp(startDateTimestamp)
                        .setEndDateTimestamp(endDateTimestamp)
                        .build()
        );

        try {
            String jsonResponse = JsonFormat.printer()
                    .preservingProtoFieldNames()
                    .omittingInsignificantWhitespace()
                    .includingDefaultValueFields()
                    .print(response);
            return ResponseEntity.ok(jsonResponse);
        } catch (IOException e) {
            log.error("Error converting response to JSON: ", e);
            return ResponseEntity.status(500).body("Internal Server Error");
        }
    }


    @GetMapping("/portfolio/symbol")
    public ResponseEntity<String> getFundPortfolioBySymbolAndDateRange(@RequestParam(name = "symbol") String symbol,
                                                                       @RequestParam(name = "start_date_timestamp") long startDateTimestamp,
                                                                       @RequestParam(name = "end_date_timestamp") long endDateTimestamp) {
        DomesticFund.DomesticFundPortfolioBySymbolAndDateRangeResponse response = domesticFundService.getDomesticFundPortfolioBySymbolAndDateRange(
                DomesticFund.DomesticFundPortfolioBySymbolAndDateRangeRequest.newBuilder()
                        .setSymbol(symbol)
                        .setStartDateTimestamp(startDateTimestamp)
                        .setEndDateTimestamp(endDateTimestamp)
                        .build()
        );

        try {
            String jsonResponse = JsonFormat.printer()
                    .preservingProtoFieldNames()
                    .omittingInsignificantWhitespace()
                    .includingDefaultValueFields()
                    .print(response);
            return ResponseEntity.ok(jsonResponse);
        } catch (IOException e) {
            log.error("Error converting response to JSON: ", e);
            return ResponseEntity.status(500).body("Internal Server Error");
        }
    }

}
