package world.willfrog.agent.tool;

import dev.langchain4j.agent.tool.Tool;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.domestic.idl.*;

import java.util.stream.Collectors;

@Component
public class MarketDataTools {

    @DubboReference
    private DomesticStockService domesticStockService;

    @DubboReference
    private DomesticFundService domesticFundService;

    @DubboReference
    private DomesticIndexService domesticIndexService;

    /**
     * 查询股票基本信息。
     *
     * @param tsCode 股票代码
     * @return 信息字符串（失败时返回错误描述）
     */
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

    /**
     * 查询股票日线行情。
     *
     * @param tsCode       股票代码
     * @param startDateStr 开始日期（YYYYMMDD）
     * @param endDateStr   结束日期（YYYYMMDD）
     * @return 日线信息字符串（失败时返回错误描述）
     */
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

    /**
     * 搜索基金信息。
     *
     * @param keyword 搜索关键词
     * @return 搜索结果字符串（失败时返回错误描述）
     */
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

    /**
     * 查询指数基本信息。
     *
     * @param tsCode 指数代码
     * @return 信息字符串（失败时返回错误描述）
     */
    @Tool("Get basic information about an index by its TS code (e.g., 000300.SH)")
    public String getIndexInfo(String tsCode) {
        try {
            DomesticIndexInfoByTsCodeRequest request = DomesticIndexInfoByTsCodeRequest.newBuilder()
                    .setTsCode(tsCode)
                    .build();
            DomesticIndexInfoByTsCodeResponse response = domesticIndexService.getDomesticIndexInfoByTsCode(request);
            if (response.hasItem()) {
                return response.getItem().toString();
            } else {
                return "No index found for TS code: " + tsCode;
            }
        } catch (Exception e) {
            return "Error fetching index info: " + e.getMessage();
        }
    }

    /**
     * 查询指数日线行情。
     *
     * @param tsCode       指数代码
     * @param startDateStr 开始日期（YYYYMMDD）
     * @param endDateStr   结束日期（YYYYMMDD）
     * @return 日线信息字符串（失败时返回错误描述）
     */
    @Tool("Get daily index market data for a specific index within a date range")
    public String getIndexDaily(String tsCode, String startDateStr, String endDateStr) {
        try {
            long startDate = Long.parseLong(startDateStr);
            long endDate = Long.parseLong(endDateStr);

            DomesticIndexDailyByTsCodeAndDateRangeRequest request = DomesticIndexDailyByTsCodeAndDateRangeRequest.newBuilder()
                    .setTsCode(tsCode)
                    .setStartDate(startDate)
                    .setEndDate(endDate)
                    .build();
            DomesticIndexDailyByTsCodeAndDateRangeResponse response = domesticIndexService.getDomesticIndexDailyByTsCodeAndDateRange(request);

            if (response.getItemsCount() > 0) {
                return response.getItemsList().stream()
                        .limit(10)
                        .map(item -> String.format("Date: %d, Close: %.2f", item.getTradeDate(), item.getClose()))
                        .collect(Collectors.joining("\n"));
            } else {
                return "No daily index data found for " + tsCode + " in range " + startDate + "-" + endDate;
            }
        } catch (Exception e) {
            return "Error fetching index daily data: " + e.getMessage();
        }
    }

    /**
     * 搜索指数信息。
     *
     * @param keyword 搜索关键词
     * @return 搜索结果字符串（失败时返回错误描述）
     */
    @Tool("Search for an index by keyword")
    public String searchIndex(String keyword) {
        try {
            DomesticIndexSearchRequest request = DomesticIndexSearchRequest.newBuilder()
                    .setQuery(keyword)
                    .build();
            DomesticIndexSearchResponse response = domesticIndexService.searchDomesticIndex(request);
            if (response.getItemsCount() > 0) {
                return response.getItemsList().stream()
                        .limit(5)
                        .map(item -> String.format("Code: %s, Name: %s", item.getTsCode(), item.getName()))
                        .collect(Collectors.joining("\n"));
            } else {
                return "No index found for keyword: " + keyword;
            }
        } catch (Exception e) {
            return "Error searching index: " + e.getMessage();
        }
    }
}
