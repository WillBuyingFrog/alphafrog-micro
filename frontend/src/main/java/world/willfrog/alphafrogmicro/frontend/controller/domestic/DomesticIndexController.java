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
import world.willfrog.alphafrogmicro.common.utils.compact.ProtoFieldExtractor;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndex.*;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexService;

@Slf4j
@Controller
@RequestMapping("/domestic/index")
public class DomesticIndexController {

    @DubboReference
    private DomesticIndexService domesticIndexService;

    @GetMapping("/info/ts_code")
    public ResponseEntity<String> getIndexInfoByTsCode(@RequestParam(name = "ts_code") String tsCode,
                                                       @RequestParam(value = "format", required = false, defaultValue = "standard") String format) {
        try {
            DomesticIndexInfoByTsCodeResponse response = domesticIndexService.getDomesticIndexInfoByTsCode(
                    DomesticIndexInfoByTsCodeRequest.newBuilder().setTsCode(tsCode).build());
            
            String jsonResponse;
            if ("compact".equals(format)) {
                // 为指数基本信息创建字段映射
                ProtoFieldExtractor.FieldMapping fieldMapping = new ProtoFieldExtractor.FieldMapping()
                        .alias("tsCode", "ts_code")
                        .alias("fullName", "full_name")
                        .alias("indexType", "index_type")
                        .alias("baseDate", "base_date")
                        .alias("basePoint", "base_point")
                        .alias("listDate", "list_date")
                        .alias("weightRule", "weight_rule")
                        .alias("expDate", "exp_date")
                        .order("tsCode", "name", "fullName", "market", "publisher", 
                              "indexType", "category", "baseDate", "basePoint", 
                              "listDate", "weightRule", "desc", "expDate");
                
                // 注意：指数基本信息返回的是单个对象，不是列表，需要特殊处理
                jsonResponse = JsonFormat.printer()
                        .preservingProtoFieldNames()
                        .omittingInsignificantWhitespace()
                        .includingDefaultValueFields()
                        .print(response.getItem());
                
                log.info("Index info by ts_code does not support compact format for single item, using standard format for tsCode: {}", tsCode);
            } else {
                // 标准格式
                jsonResponse = JsonFormat.printer()
                        .preservingProtoFieldNames()
                        .omittingInsignificantWhitespace()
                        .includingDefaultValueFields()
                        .print(response);
            }
            
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonResponse);
        } catch (Exception e) {
             log.error("Error occurred while getting index info by ts code: {}", e.getMessage());
             return ResponseEntity.status(500).body("Error occurred while getting index info by ts code");
        }
    }

    @GetMapping("/quote/daily")
    public ResponseEntity<String> getIndexDailyByTsCodeAndDateRange(@RequestParam("ts_code") String tsCode,
                                                                      @RequestParam("start_date_timestamp") long startDateTimestamp,
                                                                      @RequestParam("end_date_timestamp") long endDateTimestamp,
                                                                      @RequestParam(value = "format", required = false, defaultValue = "standard") String format) {
        try {
            DomesticIndexDailyByTsCodeAndDateRangeResponse response = domesticIndexService.getDomesticIndexDailyByTsCodeAndDateRange(
                    DomesticIndexDailyByTsCodeAndDateRangeRequest.newBuilder()
                            .setTsCode(tsCode)
                            .setStartDate(startDateTimestamp)
                            .setEndDate(endDateTimestamp)
                            .build());
            
            String jsonResponse;
            if ("compact".equals(format)) {
                // 使用CompactJsonConverter提取元信息并转换
                CompactMeta meta = CompactJsonConverter.extractMetaFromResponse(response);
                if (meta == null) {
                    meta = CompactMeta.builder().build();
                }
                // 设置请求参数中的基本信息
                meta.setTsCode(tsCode);
                meta.setStartDate(startDateTimestamp);
                meta.setEndDate(endDateTimestamp);
                
                jsonResponse = CompactJsonFormatter.toCompactJsonIndexDaily(response, meta);
                log.info("Converted index daily data to compact format for tsCode: {}, items: {}", 
                        tsCode, response.getItemsCount());
            } else {
                // 标准格式
                jsonResponse = JsonFormat.printer()
                        .preservingProtoFieldNames()
                        .omittingInsignificantWhitespace()
                        .includingDefaultValueFields()
                        .print(response);
            }
            
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonResponse);
        } catch (Exception e) {
            log.error("Error occurred while getting index dailies by ts code and date range: {}", e.getMessage());
            return ResponseEntity.status(500).body("Error occurred while getting index dailies by ts code and date range");
        }
    }

    @GetMapping("/search")
    public ResponseEntity<String> searchIndexInfo(@RequestParam(name = "query") String query,
                                                  @RequestParam(value = "format", required = false, defaultValue = "standard") String format) {
        try {
            DomesticIndexSearchResponse response = domesticIndexService.searchDomesticIndex(
                    DomesticIndexSearchRequest.newBuilder().setQuery(query).build());
            
            String jsonResponse;
            if ("compact".equals(format)) {
                // 为指数搜索数据创建字段映射
                ProtoFieldExtractor.FieldMapping fieldMapping = new ProtoFieldExtractor.FieldMapping()
                        .alias("tsCode", "ts_code")
                        .alias("fullName", "full_name")
                        .order("tsCode", "name", "fullName", "market");
                
                jsonResponse = CompactJsonFormatter.toCompactJson(response, fieldMapping);
                log.info("Converted index search data to compact format for query: {}, items: {}", 
                        query, response.getItemsCount());
            } else {
                // 标准格式
                jsonResponse = JsonFormat.printer().preservingProtoFieldNames()
                        .omittingInsignificantWhitespace()
                        .includingDefaultValueFields()
                        .print(response);
            }
            
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonResponse);
        } catch (Exception e) {
            log.error("Error occurred while searching index info: {}", e.getMessage());
            return ResponseEntity.status(500).body("Error occurred while searching index info");
        }
    }

    @GetMapping("/weight/ts_code")
    public ResponseEntity<String> getIndexWeightByTsCodeAndDateRange(@RequestParam(name = "ts_code") String tsCode,
                                                                     @RequestParam(name = "start_date_timestamp") long startDateTimestamp,
                                                                     @RequestParam(name = "end_date_timestamp") long endDateTimestamp,
                                                                     @RequestParam(value = "format", required = false, defaultValue = "standard") String format) {
        try{
            DomesticIndexWeightByTsCodeAndDateRangeResponse response =
                    domesticIndexService.getDomesticIndexWeightByTsCodeAndDateRange(
                            DomesticIndexWeightByTsCodeAndDateRangeRequest.newBuilder().setTsCode(tsCode)
                                    .setStartDate(startDateTimestamp)
                                    .setEndDate(endDateTimestamp)
                                    .build()
                    );
            
            String jsonResponse;
            if ("compact".equals(format)) {
                // 为指数权重数据创建字段映射（按指数查询视角）
                ProtoFieldExtractor.FieldMapping fieldMapping = new ProtoFieldExtractor.FieldMapping()
                        .alias("indexCode", "index_code")
                        .alias("conCode", "con_code")
                        .alias("tradeDate", "trade_date")
                        .order("indexCode", "conCode", "tradeDate", "weight");
                
                // 提取元信息
                CompactMeta meta = CompactMeta.builder()
                        .tsCode(tsCode)
                        .startDate(startDateTimestamp)
                        .endDate(endDateTimestamp)
                        .build();
                
                jsonResponse = CompactJsonFormatter.toCompactJson(response, fieldMapping, meta);
                log.info("Converted index weight data (by index) to compact format for tsCode: {}, items: {}", 
                        tsCode, response.getItemsCount());
            } else {
                // 标准格式
                jsonResponse = JsonFormat.printer().preservingProtoFieldNames()
                        .omittingInsignificantWhitespace()
                        .includingDefaultValueFields()
                        .print(response);
            }
            
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonResponse);
        } catch (Exception e) {
            log.error("Error occurred while getting index weight by ts code and date range: {}", e.getMessage());
            return ResponseEntity.status(500).body("Error occurred while getting index weight by ts code and date range");
        }
    }

    @GetMapping("/weight/con_code")
    public ResponseEntity<String> getIndexWeightByConCodeAndDateRange(@RequestParam(name = "con_code") String conCode,
                                                                      @RequestParam(name = "start_date_timestamp") long startDateTimestamp,
                                                                      @RequestParam(name = "end_date_timestamp") long endDateTimestamp,
                                                                      @RequestParam(value = "format", required = false, defaultValue = "standard") String format) {
        try {
            DomesticIndexWeightByConCodeAndDateRangeResponse response = domesticIndexService.getDomesticIndexWeightByConCodeAndDateRange(
                    DomesticIndexWeightByConCodeAndDateRangeRequest.newBuilder().setConCode(conCode).setStartDate(startDateTimestamp).setEndDate(endDateTimestamp).build());
            
            String jsonResponse;
            if ("compact".equals(format)) {
                // 为指数权重数据创建字段映射（按股票查询视角）
                ProtoFieldExtractor.FieldMapping fieldMapping = new ProtoFieldExtractor.FieldMapping()
                        .alias("indexCode", "index_code")
                        .alias("conCode", "con_code")
                        .alias("tradeDate", "trade_date")
                        .order("conCode", "indexCode", "tradeDate", "weight");
                
                // 提取元信息
                CompactMeta meta = CompactMeta.builder()
                        .tsCode(conCode)
                        .startDate(startDateTimestamp)
                        .endDate(endDateTimestamp)
                        .build();
                
                jsonResponse = CompactJsonFormatter.toCompactJson(response, fieldMapping, meta);
                log.info("Converted index weight data (by stock) to compact format for conCode: {}, items: {}", 
                        conCode, response.getItemsCount());
            } else {
                // 标准格式
                jsonResponse = JsonFormat.printer().preservingProtoFieldNames()
                        .omittingInsignificantWhitespace()
                        .includingDefaultValueFields()
                        .print(response);
            }
            
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonResponse);
        } catch (Exception e) {
            log.error("Error occurred while getting index weight by con code and date range: {}", e.getMessage());
            return ResponseEntity.status(500).body("Error occurred while getting index weight by con code and date range");
        }
    }   
    
}
