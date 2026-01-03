package world.willfrog.alphafrogmicro.frontend.controller.domestic;

import com.google.protobuf.util.JsonFormat;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import world.willfrog.alphafrogmicro.common.dto.compact.CompactMeta;
import world.willfrog.alphafrogmicro.common.utils.compact.CompactJsonConverter;
import world.willfrog.alphafrogmicro.common.utils.compact.CompactJsonFormatter;
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

    @GetMapping("/ts_code")
    public ResponseEntity<String> getStockTsCodes(@RequestParam("offset") int offset, @RequestParam("limit") int limit) {
        try {

            if(limit > 1000) {
                return ResponseEntity.badRequest().body("Limit should be no more than 1000");
            }

            DomesticStockTsCodeRequest request = DomesticStockTsCodeRequest.newBuilder().setOffset(offset).setLimit(limit).build();
            DomesticStockTsCodeResponse response = domesticStockService.getStockTsCode(request);
            String jsonResponse = JsonFormat.printer()
                    .preservingProtoFieldNames()
                    .omittingInsignificantWhitespace()
                    .includingDefaultValueFields()
                    .print(response);
            return ResponseEntity.ok(jsonResponse);
        } catch (Exception e) {
            log.error("Error occurred while getting stock ts codes: {}", e.getMessage());
            return ResponseEntity.status(500).body("Error occurred while getting stock ts codes");
        }
    }

    @GetMapping("/daily/ts_code")
    public ResponseEntity<String> getStockDailyByTsCode(@RequestParam("ts_code") String tsCode,
                                                        @RequestParam("start_date_timestamp") long startDate,
                                                        @RequestParam("end_date_timestamp") long endDate,
                                                        @RequestParam(value = "format", required = false, defaultValue = "standard") String format) {
        try {
            log.info("Getting stock daily data for tsCode: {}, startDate: {}, endDate: {}, format: {}", 
                    tsCode, startDate, endDate, format);
            
            // 构建请求
            DomesticStockDailyByTsCodeAndDateRangeRequest request =
                    DomesticStockDailyByTsCodeAndDateRangeRequest.newBuilder()
                            .setTsCode(tsCode)
                            .setStartDate(startDate)
                            .setEndDate(endDate)
                            .build();
            
            // 调用服务
            DomesticStockDailyByTsCodeAndDateRangeResponse response = 
                    domesticStockService.getStockDailyByTsCodeAndDateRange(request);
            
            String jsonResponse;
            if ("compact".equals(format)) {
                // 使用紧凑格式，并提取元数据
                CompactMeta meta = CompactJsonConverter.extractMetaFromResponse(response);
                if (meta == null) {
                    // 如果无法从响应中提取元数据，创建基础元数据
                    meta = CompactMeta.builder()
                            .tsCode(tsCode)
                            .startDate(startDate)
                            .endDate(endDate)
                            .build();
                }
                
                jsonResponse = CompactJsonFormatter.toCompactJsonStockDaily(response, meta);
                log.info("Returned compact format, size: {} bytes", jsonResponse.length());
            } else {
                // 使用标准格式
                jsonResponse = JsonFormat.printer()
                        .preservingProtoFieldNames()
                        .omittingInsignificantWhitespace()
                        .includingDefaultValueFields()
                        .print(response);
                log.info("Returned standard format, size: {} bytes", jsonResponse.length());
            }
            
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonResponse);
                    
        } catch (Exception e) {
            log.error("Error occurred while getting stock daily by ts code: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body("Error occurred while getting stock daily by ts code");
        }
    }
}
