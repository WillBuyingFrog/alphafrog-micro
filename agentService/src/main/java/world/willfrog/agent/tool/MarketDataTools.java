package world.willfrog.agent.tool;

import dev.langchain4j.agent.tool.Tool;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.domestic.idl.*;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class MarketDataTools {

    @DubboReference
    private DomesticStockService domesticStockService;

    @DubboReference
    private DomesticFundService domesticFundService;

    @Tool("Get basic information about a stock by its TS code (e.g., 000001.SZ)")
    public String getStockInfo(String tsCode) {
        try {
            DomesticStockInfoByTsCodeRequest request = DomesticStockInfoByTsCodeRequest.newBuilder()
                    .setTsCode(tsCode)
                    .build();
            DomesticStockInfoByTsCodeResponse response = domesticStockService.getStockInfoByTsCode(request);
            if (response.hasItem()) {
                return response.getItem().toString();
            } else {
                return "No stock found for TS code: " + tsCode;
            }
        } catch (Exception e) {
            return "Error fetching stock info: " + e.getMessage();
        }
    }

    @Tool("Get daily stock market data for a specific stock within a date range")
    public String getStockDaily(String tsCode, String startDateStr, String endDateStr) {
        // Simple date parsing assuming YYYYMMDD format for simplicity in this MVP
        try {
            long startDate = Long.parseLong(startDateStr);
            long endDate = Long.parseLong(endDateStr);

            DomesticStockDailyByTsCodeAndDateRangeRequest request = DomesticStockDailyByTsCodeAndDateRangeRequest.newBuilder()
                    .setTsCode(tsCode)
                    .setStartDate(startDate)
                    .setEndDate(endDate)
                    .build();
            DomesticStockDailyByTsCodeAndDateRangeResponse response = domesticStockService.getStockDailyByTsCodeAndDateRange(request);
            
            if (response.getItemsCount() > 0) {
                 return response.getItemsList().stream()
                         .limit(10) // Limit to avoid blowing up context
                         .map(item -> String.format("Date: %d, Close: %.2f", item.getTradeDate(), item.getClose()))
                         .collect(Collectors.joining("\n"));
            } else {
                return "No daily data found for " + tsCode + " in range " + startDate + "-" + endDate;
            }
        } catch (Exception e) {
            return "Error fetching stock daily data: " + e.getMessage();
        }
    }

    @Tool("Search for a fund by keyword")
    public String searchFund(String keyword) {
        try {
            DomesticFundSearchRequest request = DomesticFundSearchRequest.newBuilder()
                    .setQuery(keyword)
                    .build();
            DomesticFundSearchResponse response = domesticFundService.searchDomesticFundInfo(request);
            if (response.getItemsCount() > 0) {
                return response.getItemsList().stream()
                        .limit(5)
                        .map(item -> String.format("Code: %s, Name: %s", item.getTsCode(), item.getName()))
                        .collect(Collectors.joining("\n"));
            } else {
                return "No funds found for keyword: " + keyword;
            }
        } catch (Exception e) {
            return "Error searching fund: " + e.getMessage();
        }
    }
}
