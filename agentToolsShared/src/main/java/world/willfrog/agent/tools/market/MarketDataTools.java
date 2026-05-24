package world.willfrog.agent.tools.market;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import org.apache.dubbo.config.annotation.DubboReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.tools.dataset.DatasetRegistry;
import world.willfrog.agent.tools.dataset.DatasetWriter;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;
import world.willfrog.alphafrogmicro.domestic.idl.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

@Slf4j
@Component
public class MarketDataTools {

    @DubboReference
    private DomesticStockService domesticStockService;

    @DubboReference
    private DomesticFundService domesticFundService;

    @DubboReference
    private DomesticIndexService domesticIndexService;

    @DubboReference
    private DomesticListedAssetService domesticListedAssetService;

    private final DatasetWriter datasetWriter;
    private final DatasetRegistry datasetRegistry;
    private final AgentLlmLocalConfigLoader localConfigLoader;
    private final AgentLlmProperties llmProperties;
    private final ObjectMapper objectMapper;

    public MarketDataTools(DatasetWriter datasetWriter,
                           DatasetRegistry datasetRegistry,
                           AgentLlmLocalConfigLoader localConfigLoader,
                           AgentLlmProperties llmProperties,
                           ObjectMapper objectMapper) {
        this.datasetWriter = datasetWriter;
        this.datasetRegistry = datasetRegistry;
        this.localConfigLoader = localConfigLoader;
        this.llmProperties = llmProperties;
        this.objectMapper = objectMapper;
    }

    @Tool("查询单只或多只股票基础信息。参数要求：tsCode 支持 | 分隔的多个代码或 JSON 数组，每个代码必须是 TuShare 格式如 000001.SZ。具体批量上限必须先调用 checkParallelLimits 查询；如果没有 checkParallelLimits 工具，默认不要批量。批量示例：\"000001.SZ|600519.SH\"；批量返回 data.mode=batch、data.results、success_count、failure_count。")
    public String getStockInfo(String tsCode) {
        int maxItems = resolveMaxParallelSearchQueries();
        List<String> tsCodes = parseBatchValues(tsCode);
        String limitError = batchLimitFailureIfExceeded("getStockInfo", "tsCode", tsCodes, maxItems);
        if (limitError != null) {
            return limitError;
        }
        if (tsCodes.size() > 1) {
            return batchSearch("getStockInfo", tsCodes, this::getStockInfoSingle);
        }
        String single = tsCodes.isEmpty() ? tsCode : tsCodes.get(0);
        return getStockInfoSingle(single);
    }

    private String getStockInfoSingle(String tsCode) {
        try {
            DomesticStockInfoByTsCodeRequest request = DomesticStockInfoByTsCodeRequest.newBuilder()
                    .setTsCode(nvl(tsCode))
                    .build();
            DomesticStockInfoByTsCodeResponse response = domesticStockService.getStockInfoByTsCode(request);
            if (!response.hasItem()) {
                return fail("getStockInfo", "NO_DATA", "No stock found for ts_code", Map.of("ts_code", nvl(tsCode)));
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("ts_code", nvl(tsCode));
            data.put("item_text", response.getItem().toString());
            return ok("getStockInfo", data);
        } catch (Exception e) {
            return fail("getStockInfo", "TOOL_ERROR", "Error fetching stock info", Map.of("message", nvl(e.getMessage())));
        }
    }

    @Tool("查询股票区间日线数据。参数要求：1) tsCode 必须为“6位数字.交易所后缀”，也支持 | 分隔的多个代码或 JSON 数组，如 \"000001.SZ|600519.SH\"，具体批量上限必须先调用 checkParallelLimits 查询；如果没有 checkParallelLimits 工具，默认不要批量；2) startDateStr/endDateStr 必须严格使用 YYYYMMDD（如 20240101），禁止传毫秒时间戳或其他日期格式；3) startDateStr 必须早于或等于 endDateStr。批量返回 data.mode=batch、data.results、success_count、failure_count。")
    public String getStockDaily(String tsCode, String startDateStr, String endDateStr) {
        int maxItems = resolveMaxParallelDailyQueries();
        List<String> tsCodes = parseBatchValues(tsCode);
        String limitError = batchLimitFailureIfExceeded("getStockDaily", "tsCode", tsCodes, maxItems);
        if (limitError != null) {
            return limitError;
        }
        if (tsCodes.size() > 1) {
            return batchGetDaily("getStockDaily", tsCodes, startDateStr, endDateStr, true);
        }
        String singleTsCode = tsCodes.isEmpty() ? tsCode : tsCodes.get(0);
        return getStockDailySingle(singleTsCode, startDateStr, endDateStr);
    }

    private String getStockDailySingle(String tsCode, String startDateStr, String endDateStr) {
        String normalizedTsCode = nvl(tsCode).trim();
        String normalizedStart = compactDate(startDateStr);
        String normalizedEnd = compactDate(endDateStr);
        long startDate = convertToMsTimestamp(normalizedStart);
        long endDate = convertToMsTimestamp(normalizedEnd);
        if (startDate <= 0 || endDate <= 0) {
            return fail("getStockDaily", "INVALID_ARGUMENT", "Invalid date range, please use YYYYMMDD format (Asia/Shanghai).", Map.of(
                    "ts_code", normalizedTsCode,
                    "start_date", normalizedStart,
                    "end_date", normalizedEnd
            ));
        }

        List<String> headers = Arrays.asList("ts_code", "trade_date", "open", "high", "low", "close", "pre_close", "change", "pct_chg", "vol", "amount");
        try {
            if (datasetWriter.isEnabled() && datasetRegistry.isEnabled()) {
                return datasetRegistry.findReusable("stock_daily", normalizedTsCode, normalizedStart, normalizedEnd, headers)
                        .map(meta -> ok("getStockDaily", datasetData(
                                normalizedTsCode,
                                normalizedStart,
                                normalizedEnd,
                                headers,
                                meta.getDatasetId(),
                                meta.getRowCount(),
                                "reused",
                                true,
                                List.of()
                        )))
                        .orElseGet(() -> fetchStockDaily(normalizedTsCode, normalizedStart, normalizedEnd, headers));
            }
            return fetchStockDaily(normalizedTsCode, normalizedStart, normalizedEnd, headers);
        } catch (Exception e) {
            return fail("getStockDaily", "TOOL_ERROR", "Error fetching stock daily data", Map.of("message", nvl(e.getMessage())));
        }
    }

    @Tool("按关键词搜索股票。参数要求：keyword 必须是非空字符串，建议长度 2-40；可输入股票代码片段、股票简称、全称或拼音片段（例如 平安银行、000001、pingan）。支持 | 分隔的多个关键词或 JSON 数组，具体批量上限必须先调用 checkParallelLimits 查询；如果没有 checkParallelLimits 工具，默认不要批量。批量示例：\"平安银行|万科A\"；批量返回 data.mode=batch、data.results、success_count、failure_count。")
    public String searchStock(String keyword) {
        int maxItems = resolveMaxParallelSearchQueries();
        List<String> queries = parseBatchValues(keyword);
        String limitError = batchLimitFailureIfExceeded("searchStock", "keyword", queries, maxItems);
        if (limitError != null) {
            return limitError;
        }
        if (queries.size() > 1) {
            return batchSearch("searchStock", queries, this::searchStockSingle);
        }
        String single = queries.isEmpty() ? keyword : queries.get(0);
        return searchStockSingle(single);
    }

    private String searchStockSingle(String keyword) {
        try {
            DomesticStockSearchRequest request = DomesticStockSearchRequest.newBuilder()
                    .setQuery(nvl(keyword))
                    .build();
            DomesticStockSearchResponse response = domesticStockService.searchStock(request);
            if (response.getItemsCount() <= 0) {
                return fail("searchStock", "NO_DATA", "No stocks found for keyword", Map.of("keyword", nvl(keyword)));
            }
            List<Map<String, Object>> items = new ArrayList<>();
            response.getItemsList().stream().limit(20).forEach(item -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ts_code", item.getTsCode());
                row.put("name", item.getName());
                row.put("industry", item.getIndustry());
                items.add(row);
            });
            return ok("searchStock", Map.of(
                    "query", nvl(keyword),
                    "count", response.getItemsCount(),
                    "items", items
            ));
        } catch (Exception e) {
            return fail("searchStock", "TOOL_ERROR", "Error searching stock", Map.of("message", nvl(e.getMessage())));
        }
    }

    @Tool("按关键词搜索场外基金（公募基金），不用于 ETF 或场内上市基金。参数要求：keyword 必须是非空字符串，建议长度 2-40；可输入基金代码片段或名称关键词（例如 005827、易方达蓝筹精选）。支持 | 分隔的多个关键词或 JSON 数组，具体批量上限必须先调用 checkParallelLimits 查询；如果没有 checkParallelLimits 工具，默认不要批量。批量示例：\"易方达蓝筹精选|招商中证白酒\"；批量返回 data.mode=batch、data.results、success_count、failure_count。ETF 请改用 searchAssetInfo(assetTypes=etf)。")
    public String searchFund(String keyword) {
        int maxItems = resolveMaxParallelSearchQueries();
        List<String> queries = parseBatchValues(keyword);
        String limitError = batchLimitFailureIfExceeded("searchFund", "keyword", queries, maxItems);
        if (limitError != null) {
            return limitError;
        }
        if (queries.size() > 1) {
            return batchSearch("searchFund", queries, this::searchFundSingle);
        }
        String single = queries.isEmpty() ? keyword : queries.get(0);
        return searchFundSingle(single);
    }

    private String searchFundSingle(String keyword) {
        try {
            DomesticFundSearchRequest request = DomesticFundSearchRequest.newBuilder()
                    .setQuery(nvl(keyword))
                    .build();
            DomesticFundSearchResponse response = domesticFundService.searchDomesticFundInfo(request);
            if (response.getItemsCount() <= 0) {
                return fail("searchFund", "NO_DATA", "No funds found for keyword", Map.of("keyword", nvl(keyword)));
            }
            List<Map<String, Object>> items = new ArrayList<>();
            response.getItemsList().stream().limit(20).forEach(item -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ts_code", item.getTsCode());
                row.put("name", item.getName());
                items.add(row);
            });
            return ok("searchFund", Map.of(
                    "query", nvl(keyword),
                    "count", response.getItemsCount(),
                    "items", items
            ));
        } catch (Exception e) {
            return fail("searchFund", "TOOL_ERROR", "Error searching fund", Map.of("message", nvl(e.getMessage())));
        }
    }

    @Tool("查询单只或多只指数基础信息。参数要求：tsCode 支持 | 分隔的多个代码或 JSON 数组，每个代码必须是 TuShare 指数代码格式如 000300.SH。具体批量上限必须先调用 checkParallelLimits 查询；如果没有 checkParallelLimits 工具，默认不要批量。批量示例：\"000300.SH|000905.SH\"；批量返回 data.mode=batch、data.results、success_count、failure_count。")
    public String getIndexInfo(String tsCode) {
        int maxItems = resolveMaxParallelSearchQueries();
        List<String> tsCodes = parseBatchValues(tsCode);
        String limitError = batchLimitFailureIfExceeded("getIndexInfo", "tsCode", tsCodes, maxItems);
        if (limitError != null) {
            return limitError;
        }
        if (tsCodes.size() > 1) {
            return batchSearch("getIndexInfo", tsCodes, this::getIndexInfoSingle);
        }
        String single = tsCodes.isEmpty() ? tsCode : tsCodes.get(0);
        return getIndexInfoSingle(single);
    }

    private String getIndexInfoSingle(String tsCode) {
        try {
            DomesticIndexInfoByTsCodeRequest request = DomesticIndexInfoByTsCodeRequest.newBuilder()
                    .setTsCode(nvl(tsCode))
                    .build();
            DomesticIndexInfoByTsCodeResponse response = domesticIndexService.getDomesticIndexInfoByTsCode(request);
            if (!response.hasItem()) {
                return fail("getIndexInfo", "NO_DATA", "No index found for ts_code", Map.of("ts_code", nvl(tsCode)));
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("ts_code", nvl(tsCode));
            data.put("item_text", response.getItem().toString());
            return ok("getIndexInfo", data);
        } catch (Exception e) {
            return fail("getIndexInfo", "TOOL_ERROR", "Error fetching index info", Map.of("message", nvl(e.getMessage())));
        }
    }

    @Tool("查询指数区间日线数据。参数要求：1) tsCode 必须为“6位数字.交易所后缀”，也支持 | 分隔的多个代码或 JSON 数组，如 \"000300.SH|000905.SH\"，具体批量上限必须先调用 checkParallelLimits 查询；如果没有 checkParallelLimits 工具，默认不要批量；2) startDateStr/endDateStr 必须严格使用 YYYYMMDD（如 20240101），禁止传毫秒时间戳或其他日期格式；3) startDateStr 必须早于或等于 endDateStr。批量返回 data.mode=batch、data.results、success_count、failure_count。")
    public String getIndexDaily(String tsCode, String startDateStr, String endDateStr) {
        int maxItems = resolveMaxParallelDailyQueries();
        List<String> tsCodes = parseBatchValues(tsCode);
        String limitError = batchLimitFailureIfExceeded("getIndexDaily", "tsCode", tsCodes, maxItems);
        if (limitError != null) {
            return limitError;
        }
        if (tsCodes.size() > 1) {
            return batchGetDaily("getIndexDaily", tsCodes, startDateStr, endDateStr, false);
        }
        String singleTsCode = tsCodes.isEmpty() ? tsCode : tsCodes.get(0);
        return getIndexDailySingle(singleTsCode, startDateStr, endDateStr);
    }

    private String getIndexDailySingle(String tsCode, String startDateStr, String endDateStr) {
        String normalizedTsCode = nvl(tsCode).trim();
        String normalizedStart = compactDate(startDateStr);
        String normalizedEnd = compactDate(endDateStr);
        long startDate = convertToMsTimestamp(normalizedStart);
        long endDate = convertToMsTimestamp(normalizedEnd);
        if (startDate <= 0 || endDate <= 0) {
            return fail("getIndexDaily", "INVALID_ARGUMENT", "Invalid date range, please use YYYYMMDD format (Asia/Shanghai).", Map.of(
                    "ts_code", normalizedTsCode,
                    "start_date", normalizedStart,
                    "end_date", normalizedEnd
            ));
        }

        List<String> headers = Arrays.asList("ts_code", "trade_date", "open", "high", "low", "close", "pre_close", "change", "pct_chg", "vol", "amount");
        try {
            if (datasetWriter.isEnabled() && datasetRegistry.isEnabled()) {
                return datasetRegistry.findReusable("index_daily", normalizedTsCode, normalizedStart, normalizedEnd, headers)
                        .map(meta -> ok("getIndexDaily", datasetData(
                                normalizedTsCode,
                                normalizedStart,
                                normalizedEnd,
                                headers,
                                meta.getDatasetId(),
                                meta.getRowCount(),
                                "reused",
                                true,
                                List.of()
                        )))
                        .orElseGet(() -> fetchIndexDaily(normalizedTsCode, normalizedStart, normalizedEnd, headers));
            }
            return fetchIndexDaily(normalizedTsCode, normalizedStart, normalizedEnd, headers);
        } catch (Exception e) {
            return fail("getIndexDaily", "TOOL_ERROR", "Error fetching index daily data", Map.of("message", nvl(e.getMessage())));
        }
    }

    @Tool("按关键词搜索指数。参数要求：keyword 必须是非空字符串，建议长度 2-40；可输入指数代码片段或指数名称关键词（例如 000300、沪深300、中证500）。支持 | 分隔的多个关键词或 JSON 数组，具体批量上限必须先调用 checkParallelLimits 查询；如果没有 checkParallelLimits 工具，默认不要批量。批量示例：\"沪深300|中证500\"；批量返回 data.mode=batch、data.results、success_count、failure_count。")
    public String searchIndex(String keyword) {
        int maxItems = resolveMaxParallelSearchQueries();
        List<String> queries = parseBatchValues(keyword);
        String limitError = batchLimitFailureIfExceeded("searchIndex", "keyword", queries, maxItems);
        if (limitError != null) {
            return limitError;
        }
        if (queries.size() > 1) {
            return batchSearch("searchIndex", queries, this::searchIndexSingle);
        }
        String single = queries.isEmpty() ? keyword : queries.get(0);
        return searchIndexSingle(single);
    }

    private String searchIndexSingle(String keyword) {
        try {
            DomesticIndexSearchRequest request = DomesticIndexSearchRequest.newBuilder()
                    .setQuery(nvl(keyword))
                    .build();
            DomesticIndexSearchResponse response = domesticIndexService.searchDomesticIndex(request);
            if (response.getItemsCount() <= 0) {
                return fail("searchIndex", "NO_DATA", "No index found for keyword", Map.of("keyword", nvl(keyword)));
            }
            List<Map<String, Object>> items = new ArrayList<>();
            response.getItemsList().stream().limit(20).forEach(item -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ts_code", item.getTsCode());
                row.put("name", item.getName());
                row.put("full_name", item.getFullname());
                row.put("market", item.getMarket());
                items.add(row);
            });
            return ok("searchIndex", Map.of(
                    "query", nvl(keyword),
                    "count", response.getItemsCount(),
                    "returned", items.size(),
                    "items", items
            ));
        } catch (Exception e) {
            return fail("searchIndex", "TOOL_ERROR", "Error searching index", Map.of("message", nvl(e.getMessage())));
        }
    }

    @Tool("统一搜索股票/ETF/指数/场外基金基本信息。参数要求：query 支持 | 分隔或 JSON 数组，具体批量上限必须先调用 checkParallelLimits 查询；如果没有 checkParallelLimits 工具，默认不要批量；assetTypes 可选 stock,etf,index,off_exchange_fund（逗号分隔，默认全部）；marketScope 目前仅支持 domestic。")
    public String searchAssetInfo(String query, String assetTypes, String marketScope) {
        String scope = nvl(marketScope).trim();
        if (!scope.isBlank() && !"domestic".equalsIgnoreCase(scope)) {
            return fail("searchAssetInfo", "INVALID_ARGUMENT", "Only marketScope=domestic is supported in v1",
                    Map.of("marketScope", scope));
        }
        LinkedHashSet<String> types = parseAssetTypes(assetTypes);
        int maxItems = resolveMaxParallelSearchQueries();
        List<String> queries = parseBatchValues(query);
        String limitError = batchLimitFailureIfExceeded("searchAssetInfo", "query", queries, maxItems);
        if (limitError != null) {
            return limitError;
        }
        if (queries.size() > 1) {
            return batchSearch("searchAssetInfo", queries, q -> searchAssetInfoSingle(q, types));
        }
        String single = queries.isEmpty() ? query : queries.get(0);
        return searchAssetInfoSingle(single, types);
    }

    private String searchAssetInfoSingle(String query, LinkedHashSet<String> types) {
        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> partialErrors = new ArrayList<>();
        for (String type : types) {
            switch (type) {
                case "stock" -> mergeSearchItems(items, partialErrors, query, "stock", searchStockSingle(query));
                case "index" -> mergeSearchItems(items, partialErrors, query, "index", searchIndexSingle(query));
                case "off_exchange_fund" -> mergeSearchItems(items, partialErrors, query, "off_exchange_fund", searchFundSingle(query));
                case "etf" -> mergeSearchItems(items, partialErrors, query, "etf", searchListedAssetEtfSingle(query));
                default -> partialErrors.add(Map.of(
                        "asset_type", type,
                        "code", "INVALID_ARGUMENT",
                        "message", "Unsupported asset type"
                ));
            }
        }
        if (items.isEmpty() && !partialErrors.isEmpty()) {
            boolean allUnavailable = partialErrors.stream()
                    .allMatch(err -> "SERVICE_UNAVAILABLE".equals(String.valueOf(err.get("code"))));
            if (allUnavailable) {
                return serviceUnavailable("searchAssetInfo", "DomesticListedAssetService (A5) is not available yet");
            }
        }
        if (items.isEmpty()) {
            return fail("searchAssetInfo", "NO_DATA", "No assets found for query", Map.of(
                    "query", nvl(query),
                    "asset_types", new ArrayList<>(types)
            ));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", nvl(query));
        data.put("asset_types", new ArrayList<>(types));
        data.put("market_scope", "domestic");
        data.put("count", items.size());
        data.put("items", items);
        if (!partialErrors.isEmpty()) {
            data.put("partial_errors", partialErrors);
        }
        return ok("searchAssetInfo", data);
    }

    @Tool("查询场内资产日线（股票/ETF/指数）。参数要求：tsCode 支持 | 分隔或 JSON 数组，具体批量上限必须先调用 checkParallelLimits 查询；如果没有 checkParallelLimits 工具，默认不要批量；assetType 必填 stock|etf|index；startDate/endDate 为 YYYYMMDD；priceMode 目前仅支持 raw_ohlc。对于 ETF，若数据库中有复权因子数据，返回的 dataset 会额外包含 adj_factor 列，可用于后复权计算。")
    public String getExchangeAssetDaily(String tsCode, String assetType, String startDate, String endDate, String priceMode) {
        String type = normalizeAssetType(assetType);
        if (type.isBlank()) {
            return fail("getExchangeAssetDaily", "INVALID_ARGUMENT", "assetType is required: stock|etf|index",
                    Map.of("assetType", nvl(assetType)));
        }
        String mode = nvl(priceMode).trim().toLowerCase();
        if (!mode.isBlank() && !"raw_ohlc".equals(mode)) {
            return fail("getExchangeAssetDaily", "INVALID_ARGUMENT", "Only priceMode=raw_ohlc is supported in v1",
                    Map.of("priceMode", nvl(priceMode)));
        }
        if ("etf".equals(type)) {
            int maxItems = resolveMaxParallelDailyQueries();
            List<String> tsCodes = parseBatchValues(tsCode);
            String limitError = batchLimitFailureIfExceeded("getExchangeAssetDaily", "tsCode", tsCodes, maxItems);
            if (limitError != null) {
                return limitError;
            }
            if (tsCodes.size() > 1) {
                return batchGetListedAssetDaily("getExchangeAssetDaily", tsCodes, startDate, endDate);
            }
            String singleTsCode = tsCodes.isEmpty() ? tsCode : tsCodes.get(0);
            return fetchListedAssetDailySingle(singleTsCode, startDate, endDate, "etf", "getExchangeAssetDaily");
        }
        if ("stock".equals(type)) {
            return getStockDaily(tsCode, startDate, endDate);
        }
        if ("index".equals(type)) {
            return getIndexDaily(tsCode, startDate, endDate);
        }
        return fail("getExchangeAssetDaily", "INVALID_ARGUMENT", "Unsupported assetType: " + type,
                Map.of("assetType", type));
    }

    @Tool("查询场外基金净值序列。参数要求：tsCode 为基金代码；startDate/endDate 为 YYYYMMDD。不用于 ETF 场内日线回测。")
    public String getOffExchangeAssetDaily(String tsCode, String startDate, String endDate) {
        String normalizedTsCode = nvl(tsCode).trim();
        String normalizedStart = compactDate(startDate);
        String normalizedEnd = compactDate(endDate);
        long startMs = convertToMsTimestamp(normalizedStart);
        long endMs = convertToMsTimestamp(normalizedEnd);
        if (normalizedTsCode.isBlank() || startMs <= 0 || endMs <= 0) {
            return fail("getOffExchangeAssetDaily", "INVALID_ARGUMENT", "Invalid tsCode or date range, use YYYYMMDD",
                    Map.of("ts_code", normalizedTsCode, "start_date", normalizedStart, "end_date", normalizedEnd));
        }
        try {
            DomesticFundNavsByTsCodeAndDateRangeRequest request = DomesticFundNavsByTsCodeAndDateRangeRequest.newBuilder()
                    .setTsCode(normalizedTsCode)
                    .setStartDateTimestamp(startMs)
                    .setEndDateTimestamp(endMs)
                    .build();
            DomesticFundNavsByTsCodeAndDateRangeResponse response =
                    domesticFundService.getDomesticFundNavsByTsCodeAndDateRange(request);
            if (response.getItemsCount() <= 0) {
                return fail("getOffExchangeAssetDaily", "NO_DATA", "No fund nav data found", Map.of(
                        "ts_code", normalizedTsCode,
                        "start_date", normalizedStart,
                        "end_date", normalizedEnd
                ));
            }
            List<Map<String, Object>> previewRows = new ArrayList<>();
            response.getItemsList().stream().limit(20).forEach(item -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("nav_date", item.getNavDate());
                row.put("unit_nav", item.getUnitNav());
                row.put("adj_nav", item.getAdjNav());
                previewRows.add(row);
            });
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("ts_code", normalizedTsCode);
            data.put("start_date", normalizedStart);
            data.put("end_date", normalizedEnd);
            data.put("asset_type", "off_exchange_fund");
            data.put("rows", response.getItemsCount());
            data.put("preview_rows", previewRows);
            return ok("getOffExchangeAssetDaily", data);
        } catch (Exception e) {
            return fail("getOffExchangeAssetDaily", "TOOL_ERROR", "Error fetching fund nav data",
                    Map.of("message", nvl(e.getMessage())));
        }
    }

    @Tool("查询 ETF 复权因子时序。参数要求：tsCode/startDate/endDate；仅当 adjFactorEnabled=true 时可用。")
    public String getEtfAdj(String tsCode, String startDate, String endDate) {
        if (!isAdjFactorEnabled()) {
            return fail("getEtfAdj", "CAPABILITY_DISABLED", "ETF adj factor is disabled (adjFactorEnabled=false)",
                    Map.of("adjFactorEnabled", false));
        }
        String normalizedTsCode = nvl(tsCode).trim();
        String normalizedStart = compactDate(startDate);
        String normalizedEnd = compactDate(endDate);
        long startMs = convertToMsTimestamp(normalizedStart);
        long endMs = convertToMsTimestamp(normalizedEnd);
        if (normalizedTsCode.isBlank() || startMs <= 0 || endMs <= 0) {
            return fail("getEtfAdj", "INVALID_ARGUMENT", "Invalid tsCode or date range, use YYYYMMDD",
                    Map.of("ts_code", normalizedTsCode, "start_date", normalizedStart, "end_date", normalizedEnd));
        }
        try {
            ListedAssetAdjFactorRequest request = ListedAssetAdjFactorRequest.newBuilder()
                    .setTsCode(normalizedTsCode)
                    .setStartDate(startMs)
                    .setEndDate(endMs)
                    .build();
            ListedAssetAdjFactorResponse response = domesticListedAssetService.getListedAssetAdjFactors(request);
            if (response.getItemsCount() <= 0) {
                return fail("getEtfAdj", "NO_DATA", "No ETF adj factor data found", Map.of(
                        "ts_code", normalizedTsCode,
                        "start_date", normalizedStart,
                        "end_date", normalizedEnd
                ));
            }
            List<Map<String, Object>> previewRows = new ArrayList<>();
            response.getItemsList().stream().limit(20).forEach(item -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("trade_date", item.getTradeDate());
                row.put("adj_factor", item.getAdjFactor());
                previewRows.add(row);
            });
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("ts_code", normalizedTsCode);
            data.put("start_date", normalizedStart);
            data.put("end_date", normalizedEnd);
            data.put("asset_type", "etf");
            data.put("rows", response.getItemsCount());
            data.put("preview_rows", previewRows);
            return ok("getEtfAdj", data);
        } catch (Exception e) {
            return fail("getEtfAdj", "TOOL_ERROR", "Error fetching ETF adj factors",
                    Map.of("message", nvl(e.getMessage())));
        }
    }

    @Tool("查询 ETF 份额规模时序。参数要求：tsCode、startDate、endDate；exchange 使用 SSE/SZSE/BSE。")
    public String getListedAssetShareSize(String tsCode, String startDate, String endDate, String exchange) {
        String normalizedTsCode = nvl(tsCode).trim();
        String normalizedStart = compactDate(startDate);
        String normalizedEnd = compactDate(endDate);
        String normalizedExchange = nvl(exchange).trim().toUpperCase();
        long startMs = convertToMsTimestamp(normalizedStart);
        long endMs = convertToMsTimestamp(normalizedEnd);
        if (normalizedTsCode.isBlank() || startMs <= 0 || endMs <= 0) {
            return fail("getListedAssetShareSize", "INVALID_ARGUMENT", "Invalid tsCode or date range, use YYYYMMDD",
                    Map.of("ts_code", normalizedTsCode, "start_date", normalizedStart, "end_date", normalizedEnd));
        }
        if (!normalizedExchange.isBlank()
                && !Set.of("SSE", "SZSE", "BSE").contains(normalizedExchange)) {
            return fail("getListedAssetShareSize", "INVALID_ARGUMENT", "exchange must be SSE, SZSE, or BSE",
                    Map.of("exchange", nvl(exchange)));
        }
        try {
            DomesticEtfShareSizesByTsCodeAndDateRangeRequest request =
                    DomesticEtfShareSizesByTsCodeAndDateRangeRequest.newBuilder()
                            .setTsCode(normalizedTsCode)
                            .setStartDateTimestamp(startMs)
                            .setEndDateTimestamp(endMs)
                            .build();
            DomesticEtfShareSizesByTsCodeAndDateRangeResponse response =
                    domesticFundService.getDomesticEtfShareSizesByTsCodeAndDateRange(request);
            List<DomesticEtfShareSizeItem> items = response.getItemsList();
            if (!normalizedExchange.isBlank()) {
                items = items.stream()
                        .filter(item -> normalizedExchange.equalsIgnoreCase(nvl(item.getExchange())))
                        .toList();
            }
            if (items.isEmpty()) {
                return fail("getListedAssetShareSize", "NO_DATA", "No ETF share size data found", Map.of(
                        "ts_code", normalizedTsCode,
                        "start_date", normalizedStart,
                        "end_date", normalizedEnd,
                        "exchange", normalizedExchange
                ));
            }
            List<Map<String, Object>> previewRows = new ArrayList<>();
            items.stream().limit(20).forEach(item -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("trade_date", item.getTradeDate());
                row.put("total_share", item.hasTotalShare() ? item.getTotalShare() : null);
                row.put("total_size", item.hasTotalSize() ? item.getTotalSize() : null);
                row.put("exchange", item.getExchange());
                previewRows.add(row);
            });
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("ts_code", normalizedTsCode);
            data.put("start_date", normalizedStart);
            data.put("end_date", normalizedEnd);
            data.put("asset_type", "etf");
            if (!normalizedExchange.isBlank()) {
                data.put("exchange", normalizedExchange);
            }
            data.put("rows", items.size());
            data.put("preview_rows", previewRows);
            return ok("getListedAssetShareSize", data);
        } catch (Exception e) {
            return fail("getListedAssetShareSize", "TOOL_ERROR", "Error fetching ETF share size",
                    Map.of("message", nvl(e.getMessage())));
        }
    }

    @Tool("查询当前批量/并行查询限制。返回 search 和 daily 工具组的热加载 maxItems，以及各工具组包含哪些工具。使用任何批量参数前必须先调用本工具；如果没有本工具，默认并行查询关闭。")
    public String checkParallelLimits() {
        Map<String, Object> search = new LinkedHashMap<>();
        search.put("maxItems", resolveMaxParallelSearchQueries());
        search.put("tools", List.of(
                "searchAssetInfo",
                "searchStock",
                "searchIndex",
                "searchFund",
                "getStockInfo",
                "getIndexInfo"
        ));
        search.put("argumentFormat", "Use | separated values or JSON arrays. Do not use comma-separated values.");

        Map<String, Object> daily = new LinkedHashMap<>();
        daily.put("maxItems", resolveMaxParallelDailyQueries());
        daily.put("tools", List.of(
                "getExchangeAssetDaily",
                "getStockDaily",
                "getIndexDaily"
        ));
        daily.put("argumentFormat", "Use | separated tsCode values or JSON arrays. Do not use comma-separated values.");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("search", search);
        data.put("daily", daily);
        data.put("fallbackRule", "If checkParallelLimits is unavailable, assume batch/parallel querying is disabled and call tools with one item at a time.");
        data.put("source", "agent.llm.runtime.parallel from hot-loaded local config first, then application properties");
        return ok("checkParallelLimits", data);
    }

    private String searchListedAssetEtfSingle(String query) {
        try {
            ListedAssetSearchRequest request = ListedAssetSearchRequest.newBuilder()
                    .setQuery(nvl(query))
                    .addAssetTypes("etf")
                    .setMarketScope("domestic")
                    .setLimit(20)
                    .build();
            ListedAssetSearchResponse response = domesticListedAssetService.searchListedAssets(request);
            if (response.getItemsCount() <= 0) {
                return fail("searchAssetInfo", "NO_DATA", "No ETF found for keyword", Map.of("keyword", nvl(query)));
            }
            List<Map<String, Object>> items = new ArrayList<>();
            response.getItemsList().stream().limit(20).forEach(item -> items.add(listedAssetInfoToRow(item)));
            return ok("searchAssetInfo", Map.of(
                    "query", nvl(query),
                    "count", response.getItemsCount(),
                    "returned", items.size(),
                    "items", items
            ));
        } catch (Exception e) {
            return fail("searchAssetInfo", "TOOL_ERROR", "Error searching ETF via DomesticListedAssetService",
                    Map.of("message", nvl(e.getMessage())));
        }
    }

    private String batchGetListedAssetDaily(String toolName,
                                            List<String> tsCodes,
                                            String startDateStr,
                                            String endDateStr) {
        List<CompletableFuture<Map<String, Object>>> futures = tsCodes.stream()
                .map(code -> CompletableFuture.supplyAsync(() -> {
                    String response = fetchListedAssetDailySingle(code, startDateStr, endDateStr, "etf", toolName);
                    Map<String, Object> payload = readJsonMap(response);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("ts_code", code);
                    row.put("ok", Boolean.TRUE.equals(payload.get("ok")));
                    row.put("data", readNestedMap(payload.get("data")));
                    row.put("error", readNestedMap(payload.get("error")));
                    return row;
                }))
                .toList();

        List<Map<String, Object>> results = futures.stream().map(CompletableFuture::join).toList();
        long successCount = results.stream().filter(it -> Boolean.TRUE.equals(it.get("ok"))).count();

        return ok(toolName, Map.of(
                "mode", "batch",
                "ts_codes", tsCodes,
                "asset_type", "etf",
                "start_date", compactDate(startDateStr),
                "end_date", compactDate(endDateStr),
                "results", results,
                "success_count", successCount,
                "failure_count", Math.max(0, results.size() - successCount)
        ));
    }

    private String fetchListedAssetDailySingle(String tsCode,
                                               String startDateStr,
                                               String endDateStr,
                                               String assetType,
                                               String toolName) {
        String normalizedTsCode = nvl(tsCode).trim();
        String normalizedStart = compactDate(startDateStr);
        String normalizedEnd = compactDate(endDateStr);
        long startDate = convertToMsTimestamp(normalizedStart);
        long endDate = convertToMsTimestamp(normalizedEnd);
        if (startDate <= 0 || endDate <= 0) {
            return fail(toolName, "INVALID_ARGUMENT", "Invalid date range, please use YYYYMMDD format (Asia/Shanghai).", Map.of(
                    "ts_code", normalizedTsCode,
                    "start_date", normalizedStart,
                    "end_date", normalizedEnd
            ));
        }

        List<String> headers = Arrays.asList("ts_code", "trade_date", "open", "high", "low", "close", "pre_close", "change", "pct_chg", "vol", "amount");
        String datasetKind = "etf".equals(assetType) ? "etf_daily" : "listed_asset_daily";
        try {
            if (datasetWriter.isEnabled() && datasetRegistry.isEnabled()) {
                return datasetRegistry.findReusable(datasetKind, normalizedTsCode, normalizedStart, normalizedEnd, headers)
                        .map(meta -> ok(toolName, datasetData(
                                normalizedTsCode,
                                normalizedStart,
                                normalizedEnd,
                                headers,
                                meta.getDatasetId(),
                                meta.getRowCount(),
                                "reused",
                                true,
                                List.of()
                        )))
                        .orElseGet(() -> fetchListedAssetDailyFromService(
                                normalizedTsCode, normalizedStart, normalizedEnd, assetType, toolName, headers, datasetKind));
            }
            return fetchListedAssetDailyFromService(
                    normalizedTsCode, normalizedStart, normalizedEnd, assetType, toolName, headers, datasetKind);
        } catch (Exception e) {
            return fail(toolName, "TOOL_ERROR", "Error fetching listed asset daily data", Map.of("message", nvl(e.getMessage())));
        }
    }

    private String fetchListedAssetDailyFromService(String tsCode,
                                                    String startDateStr,
                                                    String endDateStr,
                                                    String assetType,
                                                    String toolName,
                                                    List<String> headers,
                                                    String datasetKind) {
        try {
            ListedAssetDailyRequest request = ListedAssetDailyRequest.newBuilder()
                    .setTsCode(tsCode)
                    .setAssetType(assetType)
                    .setStartDate(convertToMsTimestamp(startDateStr))
                    .setEndDate(convertToMsTimestamp(endDateStr))
                    .setPriceMode("raw_ohlc")
                    .build();
            ListedAssetDailyResponse response = domesticListedAssetService.getListedAssetDaily(request);
            if (response.getItemsCount() <= 0) {
                return fail(toolName, "NO_DATA", "No daily listed asset data found", Map.of(
                        "ts_code", tsCode,
                        "asset_type", assetType,
                        "start_date", startDateStr,
                        "end_date", endDateStr
                ));
            }

            // ── 复权因子：ETF 尝试补充 adj_factor 列 ──
            Map<Long, Double> adjFactorMap = new LinkedHashMap<>();
            if ("etf".equals(assetType)) {
                try {
                    ListedAssetAdjFactorRequest adjRequest = ListedAssetAdjFactorRequest.newBuilder()
                            .setTsCode(tsCode)
                            .setStartDate(convertToMsTimestamp(startDateStr))
                            .setEndDate(convertToMsTimestamp(endDateStr))
                            .build();
                    ListedAssetAdjFactorResponse adjResponse = domesticListedAssetService.getListedAssetAdjFactors(adjRequest);
                    adjResponse.getItemsList().forEach(item -> adjFactorMap.put(item.getTradeDate(), item.getAdjFactor()));
                } catch (Exception e) {
                    log.warn("Adj factor fetch failed for {}, continuing without: {}", tsCode, e.getMessage());
                }
            }

            List<String> effectiveHeaders = new ArrayList<>(headers);
            boolean hasAdjFactor = !adjFactorMap.isEmpty();
            if (hasAdjFactor) {
                effectiveHeaders.add("adj_factor");
            }

            if (datasetWriter.isEnabled()) {
                String runId = AgentContext.getRunId();
                String prefix = (runId != null ? runId : "unknown") + "-" + assetType;
                String datasetId = datasetWriter.writeDataset(prefix, tsCode, startDateStr, endDateStr, response.getItemsList(), effectiveHeaders, item -> {
                    List<Object> row = new ArrayList<>(Arrays.asList(
                            item.getTsCode(), item.getTradeDate(), item.getOpen(), item.getHigh(), item.getLow(), item.getClose(),
                            item.hasPreClose() ? item.getPreClose() : null,
                            item.hasChange() ? item.getChange() : null,
                            item.hasPctChg() ? item.getPctChg() : null,
                            item.hasVol() ? item.getVol() : null,
                            item.hasAmount() ? item.getAmount() : null
                    ));
                    if (hasAdjFactor) {
                        row.add(adjFactorMap.get(item.getTradeDate()));
                    }
                    return row;
                });
                if (datasetRegistry.isEnabled()) {
                    datasetRegistry.registerDataset(datasetKind, tsCode, startDateStr, endDateStr, effectiveHeaders, datasetId, response.getItemsCount());
                }
                return ok(toolName, datasetData(
                        tsCode,
                        startDateStr,
                        endDateStr,
                        effectiveHeaders,
                        datasetId,
                        response.getItemsCount(),
                        "created",
                        false,
                        List.of()
                ));
            }

            List<Map<String, Object>> previewRows = new ArrayList<>();
            response.getItemsList().stream().limit(20).forEach(item -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("trade_date", item.getTradeDate());
                row.put("close", item.getClose());
                if (hasAdjFactor) {
                    row.put("adj_factor", adjFactorMap.get(item.getTradeDate()));
                }
                previewRows.add(row);
            });
            Map<String, Object> data = datasetData(
                    tsCode,
                    startDateStr,
                    endDateStr,
                    effectiveHeaders,
                    "",
                    response.getItemsCount(),
                    "inline",
                    false,
                    previewRows
            );
            data.put("asset_type", assetType);
            return ok(toolName, data);
        } catch (Exception e) {
            return fail(toolName, "TOOL_ERROR", "Error fetching listed asset daily data", Map.of("message", nvl(e.getMessage())));
        }
    }

    private Map<String, Object> listedAssetInfoToRow(ListedAssetInfoItem item) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ts_code", item.getTsCode());
        row.put("name", item.getName());
        row.put("asset_type", item.getAssetType());
        if (item.hasExchange()) {
            row.put("exchange", item.getExchange());
        }
        if (item.hasIndexCode()) {
            row.put("index_code", item.getIndexCode());
        }
        if (item.hasIndexName()) {
            row.put("index_name", item.getIndexName());
        }
        if (item.hasEtfType()) {
            row.put("etf_type", item.getEtfType());
        }
        if (item.hasManagerName()) {
            row.put("manager_name", item.getManagerName());
        }
        return row;
    }

    private String batchSearch(String toolName, List<String> queries, Function<String, String> singleCall) {
        List<CompletableFuture<Map<String, Object>>> futures = queries.stream()
                .map(query -> CompletableFuture.supplyAsync(() -> {
                    String response = singleCall.apply(query);
                    Map<String, Object> payload = readJsonMap(response);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("query", query);
                    row.put("ok", Boolean.TRUE.equals(payload.get("ok")));
                    row.put("data", readNestedMap(payload.get("data")));
                    row.put("error", readNestedMap(payload.get("error")));
                    return row;
                }))
                .toList();

        List<Map<String, Object>> results = futures.stream().map(CompletableFuture::join).toList();
        long successCount = results.stream().filter(it -> Boolean.TRUE.equals(it.get("ok"))).count();

        return ok(toolName, Map.of(
                "mode", "batch",
                "queries", queries,
                "results", results,
                "success_count", successCount,
                "failure_count", Math.max(0, results.size() - successCount)
        ));
    }

    private String batchGetDaily(String toolName,
                                 List<String> tsCodes,
                                 String startDateStr,
                                 String endDateStr,
                                 boolean stock) {
        List<CompletableFuture<Map<String, Object>>> futures = tsCodes.stream()
                .map(code -> CompletableFuture.supplyAsync(() -> {
                    String response = stock
                            ? getStockDailySingle(code, startDateStr, endDateStr)
                            : getIndexDailySingle(code, startDateStr, endDateStr);
                    Map<String, Object> payload = readJsonMap(response);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("ts_code", code);
                    row.put("ok", Boolean.TRUE.equals(payload.get("ok")));
                    row.put("data", readNestedMap(payload.get("data")));
                    row.put("error", readNestedMap(payload.get("error")));
                    return row;
                }))
                .toList();

        List<Map<String, Object>> results = futures.stream().map(CompletableFuture::join).toList();
        long successCount = results.stream().filter(it -> Boolean.TRUE.equals(it.get("ok"))).count();

        return ok(toolName, Map.of(
                "mode", "batch",
                "ts_codes", tsCodes,
                "start_date", compactDate(startDateStr),
                "end_date", compactDate(endDateStr),
                "results", results,
                "success_count", successCount,
                "failure_count", Math.max(0, results.size() - successCount)
        ));
    }

    private List<String> parseBatchValues(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        String text = raw.trim();

        if (text.startsWith("[") && text.endsWith("]")) {
            try {
                List<?> arr = objectMapper.readValue(text, List.class);
                for (Object item : arr) {
                    String value = item == null ? "" : String.valueOf(item).trim();
                    if (!value.isBlank()) {
                        values.add(value);
                    }
                }
            } catch (Exception ignore) {
                // fallback to split mode
            }
        }

        if (values.isEmpty()) {
            String[] parts = text.split("\\|");
            for (String part : parts) {
                String value = part == null ? "" : part.trim();
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
        }

        if (values.isEmpty() && !text.isBlank()) {
            values.add(text);
        }
        return new ArrayList<>(values);
    }

    private String batchLimitFailureIfExceeded(String toolName, String argumentName, List<String> values, int maxItems) {
        if (values == null || values.size() <= Math.max(1, maxItems)) {
            return null;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("argument", argumentName);
        details.put("actual_items", values.size());
        details.put("max_items", Math.max(1, maxItems));
        details.put("requested_values", values);
        details.put("hint", "Call checkParallelLimits before batching, then split the request into batches no larger than max_items.");
        return fail(toolName, "BATCH_LIMIT_EXCEEDED", "Batch size exceeds the current parallel limit.", details);
    }

    private int resolveMaxParallelSearchQueries() {
        int local = localConfigLoader == null ? 0 : localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getParallel)
                .map(AgentLlmProperties.Parallel::getMaxParallelSearchQueries)
                .orElse(0);
        if (local > 0) {
            return clamp(local, 1, 20);
        }
        int base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getParallel)
                .map(AgentLlmProperties.Parallel::getMaxParallelSearchQueries)
                .orElse(0);
        if (base > 0) {
            return clamp(base, 1, 20);
        }
        return 3;
    }

    private int resolveMaxParallelDailyQueries() {
        int local = localConfigLoader == null ? 0 : localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getParallel)
                .map(AgentLlmProperties.Parallel::getMaxParallelDailyQueries)
                .orElse(0);
        if (local > 0) {
            return clamp(local, 1, 20);
        }
        int base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getParallel)
                .map(AgentLlmProperties.Parallel::getMaxParallelDailyQueries)
                .orElse(0);
        if (base > 0) {
            return clamp(base, 1, 20);
        }
        return 2;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private Map<String, Object> readNestedMap(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return out;
        }
        return Map.of();
    }

    private Map<String, Object> readJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String fetchStockDaily(String tsCode, String startDateStr, String endDateStr, List<String> headers) {
        try {
            long startDate = convertToMsTimestamp(startDateStr);
            long endDate = convertToMsTimestamp(endDateStr);
            DomesticStockDailyByTsCodeAndDateRangeRequest request = DomesticStockDailyByTsCodeAndDateRangeRequest.newBuilder()
                    .setTsCode(tsCode)
                    .setStartDate(startDate)
                    .setEndDate(endDate)
                    .build();
            DomesticStockDailyByTsCodeAndDateRangeResponse response = domesticStockService.getStockDailyByTsCodeAndDateRange(request);
            if (response.getItemsCount() <= 0) {
                return fail("getStockDaily", "NO_DATA", "No daily stock data found", Map.of(
                        "ts_code", tsCode,
                        "start_date", startDateStr,
                        "end_date", endDateStr
                ));
            }

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
                return ok("getStockDaily", datasetData(
                        tsCode,
                        startDateStr,
                        endDateStr,
                        headers,
                        datasetId,
                        response.getItemsCount(),
                        "created",
                        false,
                        List.of()
                ));
            }

            List<Map<String, Object>> previewRows = new ArrayList<>();
            response.getItemsList().stream().limit(20).forEach(item -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("trade_date", item.getTradeDate());
                row.put("close", item.getClose());
                previewRows.add(row);
            });
            return ok("getStockDaily", datasetData(
                    tsCode,
                    startDateStr,
                    endDateStr,
                    headers,
                    "",
                    response.getItemsCount(),
                    "inline",
                    false,
                    previewRows
            ));
        } catch (Exception e) {
            return fail("getStockDaily", "TOOL_ERROR", "Error fetching stock daily data", Map.of("message", nvl(e.getMessage())));
        }
    }

    private String fetchIndexDaily(String tsCode, String startDateStr, String endDateStr, List<String> headers) {
        try {
            long startDate = convertToMsTimestamp(startDateStr);
            long endDate = convertToMsTimestamp(endDateStr);
            DomesticIndexDailyByTsCodeAndDateRangeRequest request = DomesticIndexDailyByTsCodeAndDateRangeRequest.newBuilder()
                    .setTsCode(tsCode)
                    .setStartDate(startDate)
                    .setEndDate(endDate)
                    .build();
            DomesticIndexDailyByTsCodeAndDateRangeResponse response = domesticIndexService.getDomesticIndexDailyByTsCodeAndDateRange(request);
            if (response.getItemsCount() <= 0) {
                return fail("getIndexDaily", "NO_DATA", "No daily index data found", Map.of(
                        "ts_code", tsCode,
                        "start_date", startDateStr,
                        "end_date", endDateStr
                ));
            }

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
                return ok("getIndexDaily", datasetData(
                        tsCode,
                        startDateStr,
                        endDateStr,
                        headers,
                        datasetId,
                        response.getItemsCount(),
                        "created",
                        false,
                        List.of()
                ));
            }

            List<Map<String, Object>> previewRows = new ArrayList<>();
            response.getItemsList().stream().limit(20).forEach(item -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("trade_date", item.getTradeDate());
                row.put("close", item.getClose());
                previewRows.add(row);
            });
            return ok("getIndexDaily", datasetData(
                    tsCode,
                    startDateStr,
                    endDateStr,
                    headers,
                    "",
                    response.getItemsCount(),
                    "inline",
                    false,
                    previewRows
            ));
        } catch (Exception e) {
            return fail("getIndexDaily", "TOOL_ERROR", "Error fetching index daily data", Map.of("message", nvl(e.getMessage())));
        }
    }

    private Map<String, Object> datasetData(String tsCode,
                                            String startDate,
                                            String endDate,
                                            List<String> fields,
                                            String datasetId,
                                            int rows,
                                            String source,
                                            boolean cacheHit,
                                            List<Map<String, Object>> previewRows) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ts_code", tsCode);
        data.put("start_date", startDate);
        data.put("end_date", endDate);
        data.put("rows", rows);
        data.put("fields", fields);
        data.put("source", source);
        data.put("cache_hit", cacheHit);
        data.put("dataset_id", nvl(datasetId));
        data.put("dataset_ids", datasetId == null || datasetId.isBlank() ? List.of() : List.of(datasetId));
        if (previewRows != null && !previewRows.isEmpty()) {
            data.put("preview_rows", previewRows);
        }
        return data;
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

    private String compactDate(String raw) {
        if (raw == null) {
            return "";
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() >= 8) {
            return digits.substring(0, 8);
        }
        return raw.trim();
    }

    private String ok(String tool, Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("tool", tool);
        payload.put("data", data == null ? Map.of() : data);
        payload.put("error", null);
        return writeJson(payload);
    }

    private String fail(String tool, String code, String message, Map<String, Object> details) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", false);
        payload.put("tool", tool);
        payload.put("data", Map.of());
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", nvl(code));
        err.put("message", nvl(message));
        err.put("details", details == null ? Map.of() : details);
        payload.put("error", err);
        return writeJson(payload);
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"ok\":false,\"tool\":\"unknown\",\"error\":{\"code\":\"JSON_SERIALIZE_ERROR\",\"message\":\"" + escapeJson(nvl(e.getMessage())) + "\"}}";
        }
    }

    private String nvl(String text) {
        return text == null ? "" : text;
    }

    private String escapeJson(String text) {
        return nvl(text)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private LinkedHashSet<String> parseAssetTypes(String assetTypes) {
        LinkedHashSet<String> types = new LinkedHashSet<>();
        String raw = nvl(assetTypes).trim();
        if (raw.isBlank()) {
            types.add("stock");
            types.add("etf");
            types.add("index");
            types.add("off_exchange_fund");
            return types;
        }
        for (String part : raw.split("[,|]")) {
            String normalized = normalizeAssetType(part);
            if (!normalized.isBlank()) {
                types.add(normalized);
            }
        }
        if (types.isEmpty()) {
            types.add("stock");
            types.add("etf");
            types.add("index");
            types.add("off_exchange_fund");
        }
        return types;
    }

    private String normalizeAssetType(String assetType) {
        String type = nvl(assetType).trim().toLowerCase();
        return switch (type) {
            case "stock", "etf", "index", "off_exchange_fund" -> type;
            case "fund", "off_exchange", "offexchangefund" -> "off_exchange_fund";
            default -> type;
        };
    }

    private void mergeSearchItems(List<Map<String, Object>> items,
                                  List<Map<String, Object>> partialErrors,
                                  String query,
                                  String assetType,
                                  String responseJson) {
        Map<String, Object> payload = readJsonMap(responseJson);
        if (Boolean.TRUE.equals(payload.get("ok"))) {
            Map<String, Object> data = readNestedMap(payload.get("data"));
            Object rawItems = data.get("items");
            if (rawItems instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> row) {
                        Map<String, Object> enriched = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> entry : row.entrySet()) {
                            enriched.put(String.valueOf(entry.getKey()), entry.getValue());
                        }
                        enriched.put("asset_type", assetType);
                        items.add(enriched);
                    }
                }
            }
            return;
        }
        Map<String, Object> error = readNestedMap(payload.get("error"));
        partialErrors.add(Map.of(
                "asset_type", assetType,
                "query", nvl(query),
                "code", nvl(String.valueOf(error.getOrDefault("code", "TOOL_ERROR"))),
                "message", nvl(String.valueOf(error.getOrDefault("message", "search failed")))
        ));
    }

    private String serviceUnavailable(String tool, String message) {
        return fail(tool, "SERVICE_UNAVAILABLE", message, Map.of());
    }

    private boolean isAdjFactorEnabled() {
        Boolean local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getExecution)
                .map(AgentLlmProperties.Execution::getAdjFactorEnabled)
                .orElse(null);
        if (local != null) {
            return local;
        }
        if (llmProperties.getRuntime() != null && llmProperties.getRuntime().getExecution() != null) {
            Boolean enabled = llmProperties.getRuntime().getExecution().getAdjFactorEnabled();
            if (enabled != null) {
                return enabled;
            }
        }
        return false;
    }

    @Tool("""
        查询上市公司财务报表数据（利润表/资产负债表/现金流量表/业绩快报）。
        
        【参数规范 - 必须严格遵循】
          tsCode      - 股票代码（TuShare 格式，如 600519.SH）
          reportType  - 报告类型：income（利润表）| balancesheet（资产负债表）| cashflow（现金流量表）| express（业绩快报）
          startPeriod - 报告期开始，YYYYMMDD，如 20240101
          endPeriod   - 报告期结束，YYYYMMDD，如 20241231
        
        【⚠️ 严禁使用以下参数，会导致调用失败】
          period, date, year, month, quarter 等替代参数
        
        【正确调用示例】
        ✅ 查茅台2024年年报利润表：{"tool":"getFinancialReport","params":{"tsCode":"600519.SH","reportType":"income","startPeriod":"20240101","endPeriod":"20241231"}}
        ✅ 查茅台2024年Q1-Q3利润表：{"tool":"getFinancialReport","params":{"tsCode":"600519.SH","reportType":"income","startPeriod":"20240331","endPeriod":"20240930"}}
        
        【错误调用示例 - 会导致失败】
        ❌ {"tsCode":"600519.SH","period":"20241231","reportType":"income"}  // 用了period而不是startPeriod/endPeriod
        ❌ {"tsCode":"600519.SH","year":"2024","reportType":"income"}  // 发明year参数
        
        【报告期速查】
        - 2024年报：startPeriod=20240101, endPeriod=20241231
        - 2024半年报：startPeriod=20240101, endPeriod=20240630
        - 2024一季报：startPeriod=20240101, endPeriod=20240331
        - 2024三季报：startPeriod=20240101, endPeriod=20240930
        """)
    public String getFinancialReport(String tsCode, String reportType, String startPeriod, String endPeriod) {
        try {
            String tool = "getFinancialReport";
            String type = nvl(reportType).trim().toLowerCase();
            DomesticStockFinancialQueryRequest req = DomesticStockFinancialQueryRequest.newBuilder()
                    .setTsCode(nvl(tsCode))
                    .setStartPeriod(compactDate(startPeriod))
                    .setEndPeriod(compactDate(endPeriod))
                    .build();

            List<Map<String, Object>> items;
            switch (type) {
                case "income" -> {
                    DomesticStockIncomeQueryResponse resp = domesticStockService.queryStockIncome(req);
                    items = resp.getItemsList().stream().map(r -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("ts_code", r.getTsCode());
                        row.put("end_date", r.getEndDate());
                        row.put("report_type", r.getReportType());
                        row.put("total_revenue", r.getTotalRevenue());
                        row.put("revenue", r.getRevenue());
                        row.put("n_income", r.getNIncome());
                        row.put("n_income_attr_p", r.getNIncomeAttrP());
                        row.put("basic_eps", r.getBasicEps());
                        row.put("ebit", r.getEbit());
                        row.put("ebitda", r.getEbitda());
                        row.put("rd_exp", r.getRdExp());
                        return row;
                    }).toList();
                }
                case "balancesheet" -> {
                    DomesticStockBalancesheetQueryResponse resp = domesticStockService.queryStockBalancesheet(req);
                    items = resp.getItemsList().stream().map(r -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("ts_code", r.getTsCode());
                        row.put("end_date", r.getEndDate());
                        row.put("report_type", r.getReportType());
                        row.put("total_assets", r.getTotalAssets());
                        row.put("total_liab", r.getTotalLiab());
                        row.put("total_cur_assets", r.getTotalCurAssets());
                        row.put("total_cur_liab", r.getTotalCurLiab());
                        row.put("total_hldr_eqy_exc_min_int", r.getTotalHldrEqyExcMinInt());
                        row.put("money_cap", r.getMoneyCap());
                        row.put("inventories", r.getInventories());
                        row.put("lt_borr", r.getLtBorr());
                        row.put("st_borr", r.getStBorr());
                        return row;
                    }).toList();
                }
                case "cashflow" -> {
                    DomesticStockCashflowQueryResponse resp = domesticStockService.queryStockCashflow(req);
                    items = resp.getItemsList().stream().map(r -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("ts_code", r.getTsCode());
                        row.put("end_date", r.getEndDate());
                        row.put("report_type", r.getReportType());
                        row.put("n_cashflow_act", r.getNCashflowAct());
                        row.put("n_cashflow_inv_act", r.getNCashflowInvAct());
                        row.put("n_cash_flows_fnc_act", r.getNCashFlowsFncAct());
                        row.put("free_cashflow", r.getFreeCashflow());
                        row.put("c_fr_sale_sg", r.getCFrSaleSg());
                        return row;
                    }).toList();
                }
                case "express" -> {
                    DomesticStockExpressQueryResponse resp = domesticStockService.queryStockExpress(req);
                    items = resp.getItemsList().stream().map(r -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("ts_code", r.getTsCode());
                        row.put("end_date", r.getEndDate());
                        row.put("ann_date", r.getAnnDate());
                        row.put("revenue", r.getRevenue());
                        row.put("operate_profit", r.getOperateProfit());
                        row.put("n_income", r.getNIncome());
                        row.put("total_assets", r.getTotalAssets());
                        row.put("total_hldr_eqy_exc_min_int", r.getTotalHldrEqyExcMinInt());
                        row.put("diluted_eps", r.getDilutedEps());
                        row.put("diluted_roe", r.getDilutedRoe());
                        row.put("yoy_net_profit", r.getYoyNetProfit());
                        row.put("yoy_sales", r.getYoySales());
                        row.put("perf_summary", r.getPerfSummary());
                        return row;
                    }).toList();
                }
                default -> {
                    return fail(tool, "INVALID_ARGUMENT", "Unknown reportType: " + type +
                            ". Must be one of: income, balancesheet, cashflow, express", Map.of("reportType", type));
                }
            }

            if (items.isEmpty()) {
                return fail(tool, "NO_DATA", "No financial data found", Map.of(
                        "ts_code", nvl(tsCode),
                        "report_type", type,
                        "start_period", compactDate(startPeriod),
                        "end_period", compactDate(endPeriod)
                ));
            }

            // 写入数据集并返回 dataset_id
            String datasetId = null;
            if (datasetWriter.isEnabled()) {
                String runId = AgentContext.getRunId();
                String prefix = (runId != null ? runId : "unknown") + "-" + type;
                String startStr = compactDate(startPeriod);
                String endStr = compactDate(endPeriod);
                
                // 根据报表类型定义 headers
                List<String> headers = switch (type) {
                    case "income" -> Arrays.asList("ts_code", "end_date", "report_type", "total_revenue", "revenue", "n_income", "n_income_attr_p", "basic_eps", "ebit", "ebitda", "rd_exp");
                    case "balancesheet" -> Arrays.asList("ts_code", "end_date", "report_type", "total_assets", "total_liab", "total_cur_assets", "total_cur_liab", "total_hldr_eqy_exc_min_int", "money_cap", "inventories", "lt_borr", "st_borr");
                    case "cashflow" -> Arrays.asList("ts_code", "end_date", "report_type", "n_cashflow_act", "n_cashflow_inv_act", "n_cash_flows_fnc_act", "free_cashflow", "c_fr_sale_sg");
                    case "express" -> Arrays.asList("ts_code", "end_date", "ann_date", "revenue", "operate_profit", "n_income", "total_assets", "total_hldr_eqy_exc_min_int", "diluted_eps", "diluted_roe", "yoy_net_profit", "yoy_sales", "perf_summary");
                    default -> Arrays.asList("ts_code", "end_date");
                };
                
                datasetId = datasetWriter.writeDataset(
                        prefix, tsCode, startStr, endStr, items, headers,
                        row -> headers.stream().map(h -> row.getOrDefault(h, "")).toList()
                );
                
                if (datasetRegistry.isEnabled()) {
                    datasetRegistry.registerDataset("financial_" + type, tsCode, startStr, endStr, headers, datasetId, items.size());
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("ts_code", nvl(tsCode));
            data.put("report_type", type);
            data.put("start_period", compactDate(startPeriod));
            data.put("end_period", compactDate(endPeriod));
            data.put("count", items.size());
            data.put("items", items);
            if (datasetId != null) {
                data.put("dataset_id", datasetId);
                data.put("dataset_ids", List.of(datasetId));
            }
            return ok(tool, data);
        } catch (Exception e) {
            return fail("getFinancialReport", "TOOL_ERROR", "Error fetching financial report", Map.of("message", nvl(e.getMessage())));
        }
    }
}
