package world.willfrog.agent.tool;

import dev.langchain4j.agent.tool.Tool;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.domestic.idl.*;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;

import world.willfrog.agent.context.AgentContext;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class MarketDataTools {

    @DubboReference
    private DomesticStockService domesticStockService;

    @DubboReference
    private DomesticFundService domesticFundService;

    @DubboReference
    private DomesticIndexService domesticIndexService;

    private final DatasetWriter datasetWriter;
    private final DatasetRegistry datasetRegistry;

    public MarketDataTools(DatasetWriter datasetWriter, DatasetRegistry datasetRegistry) {
        this.datasetWriter = datasetWriter;
        this.datasetRegistry = datasetRegistry;
    }

    /**
     * 查询股票基本信息。
     *
     * @param tsCode 股票代码
     * @return 信息字符串（失败时返回错误描述）
     */
    @Tool("查询单只股票基础信息。参数要求：tsCode 必须是 TuShare 代码格式“6位数字.交易所后缀”，例如 000001.SZ、600519.SH；后缀通常为 SH/SZ/BJ（按数据源可用值）。不要只传裸代码（如 000001）。")
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
    @Tool("查询股票区间日线数据。参数要求：1) tsCode 必须为“6位数字.交易所后缀”；2) startDateStr/endDateStr 必须严格使用 YYYYMMDD（如 20240101），禁止传毫秒时间戳或其他日期格式；3) startDateStr 必须早于或等于 endDateStr。")
    public String getStockDaily(String tsCode, String startDateStr, String endDateStr) {
        try {
            long startDate = convertToMsTimestamp(startDateStr);
            long endDate = convertToMsTimestamp(endDateStr);
            if (startDate <= 0 || endDate <= 0) {
                return "Invalid date range, please use YYYYMMDD format (Asia/Shanghai).";
            }

            List<String> headers = Arrays.asList("ts_code", "trade_date", "open", "high", "low", "close", "pre_close", "change", "pct_chg", "vol", "amount");
            if (datasetWriter.isEnabled() && datasetRegistry.isEnabled()) {
                return datasetRegistry.findReusable("stock_daily", tsCode, startDateStr, endDateStr, headers)
                        .map(meta -> String.format(
                                "DATASET_REUSED: %s\nRows: %d\nRange: %s to %s\nFields: %s\n\n(Use 'execute_python' with this dataset_id to analyze data.)",
                                meta.getDatasetId(), meta.getRowCount(), meta.getStartDate(), meta.getEndDate(), String.join(", ", headers)))
                        .orElseGet(() -> fetchStockDailyAndCreateDataset(tsCode, startDateStr, endDateStr, headers));
            }

            return fetchStockDailyAndCreateDataset(tsCode, startDateStr, endDateStr, headers);
        } catch (Exception e) {
            return "Error fetching stock daily data: " + e.getMessage();
        }
    }

    private String fetchStockDailyAndCreateDataset(String tsCode, String startDateStr, String endDateStr, List<String> headers) {
        try {
            long startDate = convertToMsTimestamp(startDateStr);
            long endDate = convertToMsTimestamp(endDateStr);

            DomesticStockDailyByTsCodeAndDateRangeRequest request = DomesticStockDailyByTsCodeAndDateRangeRequest.newBuilder()
                    .setTsCode(tsCode)
                    .setStartDate(startDate)
                    .setEndDate(endDate)
                    .build();
            DomesticStockDailyByTsCodeAndDateRangeResponse response = domesticStockService.getStockDailyByTsCodeAndDateRange(request);
            
            if (response.getItemsCount() > 0) {
                if (datasetWriter.isEnabled()) {
                    String runId = AgentContext.getRunId();
                    String prefix = (runId != null ? runId : "unknown") + "-stock";
                    
                    String datasetId = datasetWriter.writeDataset(prefix, tsCode, startDateStr, endDateStr, response.getItemsList(), headers, item -> Arrays.asList(
                            item.getTsCode(), item.getTradeDate(), item.getOpen(), item.getHigh(), item.getLow(), item.getClose(), 
                            item.getPreClose(), item.getChange(), item.getPctChg(), item.getVol(), item.getAmount()
                    ));

                    if (datasetRegistry.isEnabled()) {
                        datasetRegistry.registerDataset("stock_daily", tsCode, startDateStr, endDateStr, headers, datasetId, response.getItemsCount());
                    }

                    return String.format("DATASET_CREATED: %s\nRows: %d\nRange: %s to %s\nFields: %s\n\n(Use 'execute_python' with this dataset_id to analyze data.)", 
                            datasetId, response.getItemsCount(), startDateStr, endDateStr, String.join(", ", headers));
                }

                 return response.getItemsList().stream()
                         .limit(10) // Limit to avoid blowing up context
                         .map(item -> String.format("Date: %d, Close: %.2f", item.getTradeDate(), item.getClose()))
                         .collect(Collectors.joining("\n"));
            } else {
                return "No daily data found for " + tsCode + " in range " + startDate + "-" + endDate;
            }
        }
        catch (Exception e) {
            return "Error fetching stock daily data: " + e.getMessage();
        }
    }

    /**
     * 搜索股票信息。
     *
     * @param keyword 搜索关键词
     * @return 搜索结果字符串（失败时返回错误描述）
     */
    @Tool("按关键词搜索股票。参数要求：keyword 必须是非空字符串，建议长度 2-40；可输入股票代码片段、股票简称、全称或拼音片段（例如 平安银行、000001、pingan）。")
    public String searchStock(String keyword) {
        try {
            DomesticStockSearchRequest request = DomesticStockSearchRequest.newBuilder()
                    .setQuery(keyword)
                    .build();
            DomesticStockSearchResponse response = domesticStockService.searchStock(request);
            if (response.getItemsCount() > 0) {
                return response.getItemsList().stream()
                        .limit(5)
                        .map(item -> String.format("Code: %s, Name: %s, Industry: %s", item.getTsCode(), item.getName(), item.getIndustry()))
                        .collect(Collectors.joining("\n"));
            } else {
                return "No stocks found for keyword: " + keyword;
            }
        } catch (Exception e) {
            return "Error searching stock: " + e.getMessage();
        }
    }

    /**
     * 搜索基金信息。
     *
     * @param keyword 搜索关键词
     * @return 搜索结果字符串（失败时返回错误描述）
     */
    @Tool("按关键词搜索基金。参数要求：keyword 必须是非空字符串，建议长度 2-40；可输入基金代码片段或基金名称关键词（例如 510300、沪深300ETF）。")
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
    @Tool("查询单只指数基础信息。参数要求：tsCode 必须是 TuShare 指数代码格式“6位数字.交易所后缀”，例如 000300.SH、000905.SH；不要只传裸代码。")
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
    @Tool("查询指数区间日线数据。参数要求：1) tsCode 必须为“6位数字.交易所后缀”；2) startDateStr/endDateStr 必须严格使用 YYYYMMDD（如 20240101），禁止传毫秒时间戳或其他日期格式；3) startDateStr 必须早于或等于 endDateStr。")
    public String getIndexDaily(String tsCode, String startDateStr, String endDateStr) {
        try {
            long startDate = convertToMsTimestamp(startDateStr);
            long endDate = convertToMsTimestamp(endDateStr);
            if (startDate <= 0 || endDate <= 0) {
                return "Invalid date range, please use YYYYMMDD format (Asia/Shanghai).";
            }

            List<String> headers = Arrays.asList("ts_code", "trade_date", "open", "high", "low", "close", "pre_close", "change", "pct_chg", "vol", "amount");
            if (datasetWriter.isEnabled() && datasetRegistry.isEnabled()) {
                return datasetRegistry.findReusable("index_daily", tsCode, startDateStr, endDateStr, headers)
                        .map(meta -> String.format(
                                "DATASET_REUSED: %s\nRows: %d\nRange: %s to %s\nFields: %s\n\n(Use 'execute_python' with this dataset_id to analyze data.)",
                                meta.getDatasetId(), meta.getRowCount(), meta.getStartDate(), meta.getEndDate(), String.join(", ", headers)))
                        .orElseGet(() -> fetchIndexDailyAndCreateDataset(tsCode, startDateStr, endDateStr, headers));
            }

            return fetchIndexDailyAndCreateDataset(tsCode, startDateStr, endDateStr, headers);
        } catch (Exception e) {
            return "Error fetching index daily data: " + e.getMessage();
        }
    }

    private String fetchIndexDailyAndCreateDataset(String tsCode, String startDateStr, String endDateStr, List<String> headers) {
        try {
            long startDate = convertToMsTimestamp(startDateStr);
            long endDate = convertToMsTimestamp(endDateStr);

            DomesticIndexDailyByTsCodeAndDateRangeRequest request = DomesticIndexDailyByTsCodeAndDateRangeRequest.newBuilder()
                    .setTsCode(tsCode)
                    .setStartDate(startDate)
                    .setEndDate(endDate)
                    .build();
            DomesticIndexDailyByTsCodeAndDateRangeResponse response = domesticIndexService.getDomesticIndexDailyByTsCodeAndDateRange(request);

            if (response.getItemsCount() > 0) {
                if (datasetWriter.isEnabled()) {
                    String runId = AgentContext.getRunId();
                    String prefix = (runId != null ? runId : "unknown") + "-index";

                    String datasetId = datasetWriter.writeDataset(prefix, tsCode, startDateStr, endDateStr, response.getItemsList(), headers, item -> Arrays.asList(
                            item.getTsCode(), item.getTradeDate(), item.getOpen(), item.getHigh(), item.getLow(), item.getClose(),
                            item.getPreClose(), item.getChange(), item.getPctChg(), item.getVol(), item.getAmount()
                    ));

                    if (datasetRegistry.isEnabled()) {
                        datasetRegistry.registerDataset("index_daily", tsCode, startDateStr, endDateStr, headers, datasetId, response.getItemsCount());
                    }

                    return String.format("DATASET_CREATED: %s\nRows: %d\nRange: %s to %s\nFields: %s\n\n(Use 'execute_python' with this dataset_id to analyze data.)",
                            datasetId, response.getItemsCount(), startDateStr, endDateStr, String.join(", ", headers));
                }

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
    @Tool("按关键词搜索指数。参数要求：keyword 必须是非空字符串，建议长度 2-40；可输入指数代码片段或指数名称关键词（例如 000300、沪深300、中证500）。")
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

    private long convertToMsTimestamp(String dateStr) {
        if (dateStr == null) {
            return -1;
        }
        String raw = dateStr.trim();
        if (raw.isEmpty()) {
            return -1;
        }
        if (raw.matches("\\d{13}")) {
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        Long converted = DateConvertUtils.convertDateStrToLong(raw, "yyyyMMdd");
        if (converted == null || converted <= 0) {
            return -1;
        }
        return converted;
    }
}
