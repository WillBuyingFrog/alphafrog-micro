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
import world.willfrog.alphafrogmicro.domestic.idl.DomesticFundService;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticListedAssetService;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticEtfShareSizesByTsCodeAndDateRangeRequest;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetAdjFactorRequest;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetDailyRequest;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetInfoRequest;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetSearchRequest;

@Controller
@RequestMapping("/domestic/listed-asset")
@Slf4j
public class DomesticListedAssetController {

    @DubboReference(timeout = 35000)
    private DomesticListedAssetService domesticListedAssetService;

    @DubboReference(timeout = 35000)
    private DomesticFundService domesticFundService;

    @GetMapping("/info/ts_code")
    public ResponseEntity<String> getListedAssetInfo(@RequestParam(name = "ts_code") String tsCode,
                                                     @RequestParam(name = "asset_type") String assetType) {
        try {
            ListedAssetInfoRequest request = ListedAssetInfoRequest.newBuilder()
                    .setTsCode(tsCode)
                    .setAssetType(assetType)
                    .build();
            return json(domesticListedAssetService.getListedAssetInfo(request));
        } catch (Exception e) {
            log.error("Error occurred while getting listed asset info: tsCode={}, assetType={}", tsCode, assetType, e);
            return ResponseEntity.status(500).body("Error occurred while getting listed asset info");
        }
    }

    @GetMapping("/search")
    public ResponseEntity<String> searchListedAssets(@RequestParam("query") String query,
                                                     @RequestParam(name = "asset_types", required = false) String assetTypes,
                                                     @RequestParam(name = "market_scope", required = false, defaultValue = "domestic") String marketScope,
                                                     @RequestParam(name = "limit", required = false, defaultValue = "20") int limit) {
        try {
            ListedAssetSearchRequest.Builder request = ListedAssetSearchRequest.newBuilder()
                    .setQuery(query)
                    .setMarketScope(marketScope)
                    .setLimit(limit);
            for (String assetType : splitCsv(assetTypes)) {
                request.addAssetTypes(assetType);
            }
            return json(domesticListedAssetService.searchListedAssets(request.build()));
        } catch (Exception e) {
            log.error("Error occurred while searching listed assets: query={}", query, e);
            return ResponseEntity.status(500).body("Error occurred while searching listed assets");
        }
    }

    @GetMapping("/daily/ts_code")
    public ResponseEntity<String> getListedAssetDaily(@RequestParam(name = "ts_code") String tsCode,
                                                      @RequestParam(name = "asset_type") String assetType,
                                                      @RequestParam(name = "start_date_timestamp") long startDate,
                                                      @RequestParam(name = "end_date_timestamp") long endDate,
                                                      @RequestParam(name = "price_mode", required = false, defaultValue = "raw_ohlc") String priceMode) {
        try {
            ListedAssetDailyRequest request = ListedAssetDailyRequest.newBuilder()
                    .setTsCode(tsCode)
                    .setAssetType(assetType)
                    .setStartDate(startDate)
                    .setEndDate(endDate)
                    .setPriceMode(priceMode)
                    .build();
            return json(domesticListedAssetService.getListedAssetDaily(request));
        } catch (Exception e) {
            log.error("Error occurred while getting listed asset daily: tsCode={}, assetType={}", tsCode, assetType, e);
            return ResponseEntity.status(500).body("Error occurred while getting listed asset daily");
        }
    }

    @GetMapping("/adjustment")
    public ResponseEntity<String> getListedAssetAdjustment(@RequestParam(name = "ts_code") String tsCode,
                                                          @RequestParam(name = "asset_type", defaultValue = "etf") String assetType,
                                                          @RequestParam(name = "start_date_timestamp") long startDate,
                                                          @RequestParam(name = "end_date_timestamp") long endDate) {
        if (!"etf".equalsIgnoreCase(assetType)) {
            return ResponseEntity.badRequest().body("Only asset_type=etf supports adjustment in v1");
        }
        try {
            ListedAssetAdjFactorRequest request = ListedAssetAdjFactorRequest.newBuilder()
                    .setTsCode(tsCode)
                    .setStartDate(startDate)
                    .setEndDate(endDate)
                    .build();
            return json(domesticListedAssetService.getListedAssetAdjFactors(request));
        } catch (Exception e) {
            log.error("Error occurred while getting ETF adjustment: tsCode={}", tsCode, e);
            return ResponseEntity.status(500).body("Error occurred while getting ETF adjustment");
        }
    }

    @GetMapping("/share-size")
    public ResponseEntity<String> getListedAssetShareSize(@RequestParam(name = "ts_code") String tsCode,
                                                          @RequestParam(name = "asset_type", defaultValue = "etf") String assetType,
                                                          @RequestParam(name = "start_date_timestamp") long startDate,
                                                          @RequestParam(name = "end_date_timestamp") long endDate) {
        if (!"etf".equalsIgnoreCase(assetType)) {
            return ResponseEntity.badRequest().body("Only asset_type=etf supports share size in v1");
        }
        try {
            DomesticEtfShareSizesByTsCodeAndDateRangeRequest request =
                    DomesticEtfShareSizesByTsCodeAndDateRangeRequest.newBuilder()
                            .setTsCode(tsCode)
                            .setStartDateTimestamp(startDate)
                            .setEndDateTimestamp(endDate)
                            .build();
            return json(domesticFundService.getDomesticEtfShareSizesByTsCodeAndDateRange(request));
        } catch (Exception e) {
            log.error("Error occurred while getting ETF share size: tsCode={}", tsCode, e);
            return ResponseEntity.status(500).body("Error occurred while getting ETF share size");
        }
    }

    private ResponseEntity<String> json(com.google.protobuf.Message response) throws Exception {
        String jsonResponse = JsonFormat.printer()
                .preservingProtoFieldNames()
                .omittingInsignificantWhitespace()
                .includingDefaultValueFields()
                .print(response);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonResponse);
    }

    private String[] splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return new String[0];
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toArray(String[]::new);
    }
}
