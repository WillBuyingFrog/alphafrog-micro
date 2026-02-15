package world.willfrog.agent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;
import world.willfrog.agent.config.AgentLlmProperties;
import world.willfrog.agent.context.AgentContext;
import world.willfrog.agent.service.AgentLlmLocalConfigLoader;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;
import world.willfrog.alphafrogmicro.domestic.idl.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

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

    @Tool("查询单只股票基础信息。参数要求：tsCode 必须是 TuShare 代码格式“6位数字.交易所后缀”，例如 000001.SZ、600519.SH；后缀通常为 SH/SZ/BJ（按数据源可用值）。不要只传裸代码（如 000001）。")
    public String getStockInfo(String tsCode) {
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

    @Tool("查询股票区间日线数据。参数要求：1) tsCode 必须为“6位数字.交易所后缀”；2) startDateStr/endDateStr 必须严格使用 YYYYMMDD（如 20240101），禁止传毫秒时间戳或其他日期格式；3) startDateStr 必须早于或等于 endDateStr。")
    public String getStockDaily(String tsCode, String startDateStr, String endDateStr) {
        List<String> tsCodes = parseBatchValues(tsCode, resolveMaxParallelDailyQueries());
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

    @Tool("按关键词搜索股票。参数要求：keyword 必须是非空字符串，建议长度 2-40；可输入股票代码片段、股票简称、全称或拼音片段（例如 平安银行、000001、pingan）。")
    public String searchStock(String keyword) {
        List<String> queries = parseBatchValues(keyword, resolveMaxParallelSearchQueries());
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

    @Tool("按关键词搜索基金。参数要求：keyword 必须是非空字符串，建议长度 2-40；可输入基金代码片段或基金名称关键词（例如 510300、沪深300ETF）。")
    public String searchFund(String keyword) {
        List<String> queries = parseBatchValues(keyword, resolveMaxParallelSearchQueries());
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

    @Tool("查询单只指数基础信息。参数要求：tsCode 必须是 TuShare 指数代码格式“6位数字.交易所后缀”，例如 000300.SH、000905.SH；不要只传裸代码。")
    public String getIndexInfo(String tsCode) {
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

    @Tool("查询指数区间日线数据。参数要求：1) tsCode 必须为“6位数字.交易所后缀”；2) startDateStr/endDateStr 必须严格使用 YYYYMMDD（如 20240101），禁止传毫秒时间戳或其他日期格式；3) startDateStr 必须早于或等于 endDateStr。")
    public String getIndexDaily(String tsCode, String startDateStr, String endDateStr) {
        List<String> tsCodes = parseBatchValues(tsCode, resolveMaxParallelDailyQueries());
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

    @Tool("按关键词搜索指数。参数要求：keyword 必须是非空字符串，建议长度 2-40；可输入指数代码片段或指数名称关键词（例如 000300、沪深300、中证500）。")
    public String searchIndex(String keyword) {
        List<String> queries = parseBatchValues(keyword, resolveMaxParallelSearchQueries());
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

    private List<String> parseBatchValues(String raw, int maxItems) {
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
                    if (values.size() >= maxItems) {
                        break;
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
                if (values.size() >= maxItems) {
                    break;
                }
            }
        }

        if (values.isEmpty() && !text.isBlank()) {
            values.add(text);
        }
        return new ArrayList<>(values).subList(0, Math.min(values.size(), Math.max(1, maxItems)));
    }

    private int resolveMaxParallelSearchQueries() {
        int local = localConfigLoader.current()
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
        int local = localConfigLoader.current()
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
}
