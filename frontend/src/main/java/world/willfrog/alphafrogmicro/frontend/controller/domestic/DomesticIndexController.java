package world.willfrog.alphafrogmicro.frontend.controller.domestic;

import com.google.protobuf.util.JsonFormat;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndex.*;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexService;

@Slf4j
@Controller
@RequestMapping("/domestic/index")
public class DomesticIndexController {

    @DubboReference
    private DomesticIndexService domesticIndexService;

    @GetMapping("/info/ts_code")
    public ResponseEntity<String> getIndexInfoByTsCode(@RequestParam(name = "ts_code") String tsCode) {
        try {
            DomesticIndexInfoByTsCodeResponse response = domesticIndexService.getDomesticIndexInfoByTsCode(
                    DomesticIndexInfoByTsCodeRequest.newBuilder().setTsCode(tsCode).build());
            String jsonResponse = JsonFormat.printer()
                    .preservingProtoFieldNames()
                    .omittingInsignificantWhitespace()
                    .includingDefaultValueFields()
                    .print(response);
            return ResponseEntity.ok(jsonResponse);
        } catch (Exception e) {
             log.error("Error occurred while getting index info by ts code: {}", e.getMessage());
             return ResponseEntity.status(500).body("Error occurred while getting index info by ts code");
        }
    }

    @GetMapping("/quote/daily")
    public ResponseEntity<String> getIndexDailyByTsCodeAndDateRange(@RequestParam("ts_code") String tsCode,
                                                                      @RequestParam("start_date_timestamp") long startDateTimestamp,
                                                                      @RequestParam("end_date_timestamp") long endDateTimestamp) {
        try {
            DomesticIndexDailyByTsCodeAndDateRangeResponse response = domesticIndexService.getDomesticIndexDailyByTsCodeAndDateRange(
                    DomesticIndexDailyByTsCodeAndDateRangeRequest.newBuilder()
                            .setTsCode(tsCode)
                            .setStartDate(startDateTimestamp)
                            .setEndDate(endDateTimestamp)
                            .build());
            String jsonResponse = JsonFormat.printer()
                    .preservingProtoFieldNames()
                    .omittingInsignificantWhitespace()
                    .includingDefaultValueFields()
                    .print(response);
            return ResponseEntity.ok(jsonResponse);
        } catch (Exception e) {
            log.error("Error occurred while getting index dailies by ts code and date range: {}", e.getMessage());
            return ResponseEntity.status(500).body("Error occurred while getting index dailies by ts code and date range");
        }
    }

    @GetMapping("/search")
    public ResponseEntity<String> searchIndexInfo(@RequestParam(name = "query") String query) {
        try {
            DomesticIndexSearchResponse response = domesticIndexService.searchDomesticIndex(
                    DomesticIndexSearchRequest.newBuilder().setQuery(query).build());
            String jsonResponse = JsonFormat.printer().preservingProtoFieldNames()
                    .omittingInsignificantWhitespace()
                    .includingDefaultValueFields()
                    .print(response);
            return ResponseEntity.ok(jsonResponse);
        } catch (Exception e) {
            log.error("Error occurred while searching index info: {}", e.getMessage());
            return ResponseEntity.status(500).body("Error occurred while searching index info");
        }
    }

    @GetMapping("/weight/ts_code")
    public ResponseEntity<String> getIndexWeightByTsCodeAndDateRange(@RequestParam(name = "ts_code") String tsCode,
                                                                     @RequestParam(name = "start_date_timestamp") long startDateTimestamp,
                                                                     @RequestParam(name = "end_date_timestamp") long endDateTimestamp) {
        try{
            DomesticIndexWeightByTsCodeAndDateRangeResponse response =
                    domesticIndexService.getDomesticIndexWeightByTsCodeAndDateRange(
                            DomesticIndexWeightByTsCodeAndDateRangeRequest.newBuilder().setTsCode(tsCode)
                                    .setStartDate(startDateTimestamp)
                                    .setEndDate(endDateTimestamp)
                                    .build()
                    );
            String jsonResponse = JsonFormat.printer().preservingProtoFieldNames()
                    .omittingInsignificantWhitespace()
                    .includingDefaultValueFields()
                    .print(response);
            return ResponseEntity.ok(jsonResponse);
        } catch (Exception e) {
            log.error("Error occurred while getting index weight by ts code and date range: {}", e.getMessage());
            return ResponseEntity.status(500).body("Error occurred while getting index weight by ts code and date range");
        }
    }

    @GetMapping("/weight/con_code")
    public ResponseEntity<String> getIndexWeightByConCodeAndDateRange(@RequestParam(name = "con_code") String conCode,
                                                                      @RequestParam(name = "start_date_timestamp") long startDateTimestamp,
                                                                      @RequestParam(name = "end_date_timestamp") long endDateTimestamp) {
        try {
            DomesticIndexWeightByConCodeAndDateRangeResponse response = domesticIndexService.getDomesticIndexWeightByConCodeAndDateRange(
                    DomesticIndexWeightByConCodeAndDateRangeRequest.newBuilder().setConCode(conCode).setStartDate(startDateTimestamp).setEndDate(endDateTimestamp).build());
            String jsonResponse = JsonFormat.printer().preservingProtoFieldNames()
                    .omittingInsignificantWhitespace()
                    .includingDefaultValueFields()
                    .print(response);
            return ResponseEntity.ok(jsonResponse);
        } catch (Exception e) {
            log.error("Error occurred while getting index weight by con code and date range: {}", e.getMessage());
            return ResponseEntity.status(500).body("Error occurred while getting index weight by con code and date range");
        }
    }   
    
}
