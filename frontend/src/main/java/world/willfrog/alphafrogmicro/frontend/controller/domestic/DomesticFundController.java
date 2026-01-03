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
    public ResponseEntity<String> getFundInfoByTsCode(@RequestParam(name = "ts_code") String tsCode,
                                                      @RequestParam(value = "format", required = false, defaultValue = "standard") String format) {
        try {
            log.info("Getting fund info for tsCode: {}, format: {}", tsCode, format);
            
            DomesticFund.DomesticFundInfoByTsCodeResponse response = domesticFundService.getDomesticFundInfoByTsCode(
                    DomesticFund.DomesticFundInfoByTsCodeRequest.newBuilder().setTsCode(tsCode).build()
            );

            String jsonResponse;
            if ("compact".equals(format)) {
                // 使用紧凑格式
                CompactMeta meta = CompactMeta.builder()
                        .tsCode(tsCode)
                        .build();
                jsonResponse = CompactJsonFormatter.toCompactJson(response, null, meta);
                log.info("Returned compact format for fund info, size: {} bytes", jsonResponse.length());
            } else {
                // 使用标准格式
                jsonResponse = JsonFormat.printer()
                        .preservingProtoFieldNames()
                        .omittingInsignificantWhitespace()
                        .includingDefaultValueFields()
                        .print(response);
                log.info("Returned standard format for fund info, size: {} bytes", jsonResponse.length());
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonResponse);
        } catch (Exception e) {
            log.error("Error converting response to JSON for tsCode: " + tsCode, e);
            return ResponseEntity.status(500).body("Internal Server Error");
        }
    }

    @GetMapping("/search")
    public ResponseEntity<String> searchFundInfo(@RequestParam(name = "query") String query,
                                                 @RequestParam(value = "format", required = false, defaultValue = "standard") String format) {
        try {
            log.info("Searching fund info for query: {}, format: {}", query, format);
            
            DomesticFund.DomesticFundSearchResponse response = domesticFundService.searchDomesticFundInfo(
                    DomesticFund.DomesticFundSearchRequest.newBuilder().setQuery(query).build()
            );

            String jsonResponse;
            if ("compact".equals(format)) {
                // 使用紧凑格式
                CompactMeta meta = CompactMeta.builder()
                        .query(query)
                        .build();
                jsonResponse = CompactJsonFormatter.toCompactJson(response, null, meta);
                log.info("Returned compact format for fund search, size: {} bytes", jsonResponse.length());
            } else {
                // 使用标准格式
                jsonResponse = JsonFormat.printer()
                        .preservingProtoFieldNames()
                        .omittingInsignificantWhitespace()
                        .includingDefaultValueFields()
                        .print(response);
                log.info("Returned standard format for fund search, size: {} bytes", jsonResponse.length());
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonResponse);
        } catch (Exception e) {
            log.error("Error converting response to JSON for query: " + query, e);
            return ResponseEntity.status(500).body("Internal Server Error");
        }
    }


    @GetMapping("/nav/ts_code")
    public ResponseEntity<String> getFundNavByTsCodeAndDateRange(@RequestParam(name = "ts_code") String tsCode,
                                                                   @RequestParam(name = "start_date_timestamp") long startDateTimestamp,
                                                                   @RequestParam(name = "end_date_timestamp") long endDateTimestamp,
                                                                   @RequestParam(value = "format", required = false, defaultValue = "standard") String format) {
        try {
            log.info("Getting fund nav data for tsCode: {}, startDate: {}, endDate: {}, format: {}", 
                    tsCode, startDateTimestamp, endDateTimestamp, format);
            
            DomesticFund.DomesticFundNavsByTsCodeAndDateRangeResponse response = domesticFundService.getDomesticFundNavsByTsCodeAndDateRange(
                    DomesticFund.DomesticFundNavsByTsCodeAndDateRangeRequest.newBuilder()
                            .setTsCode(tsCode)
                            .setStartDateTimestamp(startDateTimestamp)
                            .setEndDateTimestamp(endDateTimestamp)
                            .build()
            );

            String jsonResponse;
            if ("compact".equals(format)) {
                // 使用紧凑格式，并提取元数据
                CompactMeta meta = CompactJsonConverter.extractMetaFromResponse(response);
                if (meta == null) {
                    // 如果无法从响应中提取元数据，创建基础元数据
                    meta = CompactMeta.builder()
                            .tsCode(tsCode)
                            .startDate(startDateTimestamp)
                            .endDate(endDateTimestamp)
                            .build();
                }
                
                jsonResponse = CompactJsonFormatter.toCompactJsonFundNav(response, meta);
                log.info("Returned compact format for fund nav, size: {} bytes", jsonResponse.length());
            } else {
                // 使用标准格式
                jsonResponse = JsonFormat.printer()
                        .preservingProtoFieldNames()
                        .omittingInsignificantWhitespace()
                        .includingDefaultValueFields()
                        .print(response);
                log.info("Returned standard format for fund nav, size: {} bytes", jsonResponse.length());
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonResponse);
        } catch (Exception e) {
            log.error("Error converting response to JSON for tsCode: " + tsCode, e);
            return ResponseEntity.status(500).body("Internal Server Error");
        }
    }

    @GetMapping("/portfolio/ts_code")
    public ResponseEntity<String> getFundPortfolioByTsCodeAndDateRange(@RequestParam(name = "ts_code") String tsCode,
                                                                       @RequestParam(name = "start_date_timestamp") long startDateTimestamp,
                                                                       @RequestParam(name = "end_date_timestamp") long endDateTimestamp,
                                                                       @RequestParam(value = "format", required = false, defaultValue = "standard") String format) {
        try {
            log.info("Getting fund portfolio data for tsCode: {}, startDate: {}, endDate: {}, format: {}", 
                    tsCode, startDateTimestamp, endDateTimestamp, format);
            
            DomesticFund.DomesticFundPortfolioByTsCodeAndDateRangeResponse response = domesticFundService.getDomesticFundPortfolioByTsCodeAndDateRange(
                    DomesticFund.DomesticFundPortfolioByTsCodeAndDateRangeRequest.newBuilder()
                            .setTsCode(tsCode)
                            .setStartDateTimestamp(startDateTimestamp)
                            .setEndDateTimestamp(endDateTimestamp)
                            .build()
            );

            String jsonResponse;
            if ("compact".equals(format)) {
                // 使用紧凑格式，并提取元数据
                CompactMeta meta = CompactJsonConverter.extractMetaFromResponse(response);
                if (meta == null) {
                    // 如果无法从响应中提取元数据，创建基础元数据
                    meta = CompactMeta.builder()
                            .tsCode(tsCode)
                            .startDate(startDateTimestamp)
                            .endDate(endDateTimestamp)
                            .build();
                }
                
                jsonResponse = CompactJsonFormatter.toCompactJson(response, null, meta);
                log.info("Returned compact format for fund portfolio by tsCode, size: {} bytes", jsonResponse.length());
            } else {
                // 使用标准格式
                jsonResponse = JsonFormat.printer()
                        .preservingProtoFieldNames()
                        .omittingInsignificantWhitespace()
                        .includingDefaultValueFields()
                        .print(response);
                log.info("Returned standard format for fund portfolio by tsCode, size: {} bytes", jsonResponse.length());
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonResponse);
        } catch (Exception e) {
            log.error("Error converting response to JSON for tsCode: " + tsCode, e);
            return ResponseEntity.status(500).body("Internal Server Error");
        }
    }


    @GetMapping("/portfolio/symbol")
    public ResponseEntity<String> getFundPortfolioBySymbolAndDateRange(@RequestParam(name = "symbol") String symbol,
                                                                       @RequestParam(name = "start_date_timestamp") long startDateTimestamp,
                                                                       @RequestParam(name = "end_date_timestamp") long endDateTimestamp,
                                                                       @RequestParam(value = "format", required = false, defaultValue = "standard") String format) {
        try {
            log.info("Getting fund portfolio data for symbol: {}, startDate: {}, endDate: {}, format: {}", 
                    symbol, startDateTimestamp, endDateTimestamp, format);
            
            DomesticFund.DomesticFundPortfolioBySymbolAndDateRangeResponse response = domesticFundService.getDomesticFundPortfolioBySymbolAndDateRange(
                    DomesticFund.DomesticFundPortfolioBySymbolAndDateRangeRequest.newBuilder()
                            .setSymbol(symbol)
                            .setStartDateTimestamp(startDateTimestamp)
                            .setEndDateTimestamp(endDateTimestamp)
                            .build()
            );

            String jsonResponse;
            if ("compact".equals(format)) {
                // 使用紧凑格式，并提取元数据
                CompactMeta meta = CompactJsonConverter.extractMetaFromResponse(response);
                if (meta == null) {
                    // 如果无法从响应中提取元数据，创建基础元数据
                    meta = CompactMeta.builder()
                            .symbol(symbol)
                            .startDate(startDateTimestamp)
                            .endDate(endDateTimestamp)
                            .build();
                }
                
                jsonResponse = CompactJsonFormatter.toCompactJson(response, null, meta);
                log.info("Returned compact format for fund portfolio by symbol, size: {} bytes", jsonResponse.length());
            } else {
                // 使用标准格式
                jsonResponse = JsonFormat.printer()
                        .preservingProtoFieldNames()
                        .omittingInsignificantWhitespace()
                        .includingDefaultValueFields()
                        .print(response);
                log.info("Returned standard format for fund portfolio by symbol, size: {} bytes", jsonResponse.length());
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonResponse);
        } catch (Exception e) {
            log.error("Error converting response to JSON for symbol: " + symbol, e);
            return ResponseEntity.status(500).body("Internal Server Error");
        }
    }

}
