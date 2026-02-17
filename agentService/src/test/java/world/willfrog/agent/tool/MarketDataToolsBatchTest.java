package world.willfrog.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.config.AgentLlmProperties;
import world.willfrog.agent.service.AgentLlmLocalConfigLoader;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockDailyItem;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockDailyByTsCodeAndDateRangeResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockInfoSimpleItem;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockSearchResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockService;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketDataToolsBatchTest {

    @Mock
    private DatasetWriter datasetWriter;
    @Mock
    private DatasetRegistry datasetRegistry;
    @Mock
    private AgentLlmLocalConfigLoader localConfigLoader;

    private MarketDataTools tools;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        AgentLlmProperties properties = new AgentLlmProperties();
        AgentLlmProperties.Runtime runtime = new AgentLlmProperties.Runtime();
        AgentLlmProperties.Parallel parallel = new AgentLlmProperties.Parallel();
        parallel.setMaxParallelSearchQueries(5);
        parallel.setMaxParallelDailyQueries(5);
        runtime.setParallel(parallel);
        properties.setRuntime(runtime);
        lenient().when(localConfigLoader.current()).thenReturn(Optional.of(properties));

        tools = new MarketDataTools(datasetWriter, datasetRegistry, localConfigLoader, properties, objectMapper);
        lenient().when(datasetWriter.isEnabled()).thenReturn(false);
        lenient().when(datasetRegistry.isEnabled()).thenReturn(false);

        DomesticStockService stockService = mock(DomesticStockService.class);
        ReflectionTestUtils.setField(tools, "domesticStockService", stockService);

        DomesticStockInfoSimpleItem searchItem = DomesticStockInfoSimpleItem.newBuilder()
                .setTsCode("000001.SZ")
                .setName("平安银行")
                .setIndustry("银行")
                .build();
        DomesticStockSearchResponse searchResponse = DomesticStockSearchResponse.newBuilder()
                .addItems(searchItem)
                .build();

        DomesticStockDailyItem dailyItem = DomesticStockDailyItem.newBuilder()
                .setTsCode("000001.SZ")
                .setTradeDate(20240102L)
                .setOpen(10.0)
                .setHigh(10.5)
                .setLow(9.8)
                .setClose(10.2)
                .setPreClose(10.0)
                .setChange(0.2)
                .setPctChg(2.0)
                .setVol(1000.0)
                .setAmount(10000.0)
                .build();
        DomesticStockDailyByTsCodeAndDateRangeResponse dailyResponse = DomesticStockDailyByTsCodeAndDateRangeResponse.newBuilder()
                .addItems(dailyItem)
                .build();

        lenient().when(stockService.searchStock(any())).thenReturn(searchResponse);
        lenient().when(stockService.getStockDailyByTsCodeAndDateRange(any())).thenReturn(dailyResponse);
    }

    @Test
    void searchStock_shouldSupportBatchKeyword() throws Exception {
        String response = tools.searchStock("平安|万科");
        Map<?, ?> root = objectMapper.readValue(response, Map.class);
        Map<?, ?> data = (Map<?, ?>) root.get("data");

        assertEquals("batch", data.get("mode"));
        assertEquals(2, ((List<?>) data.get("results")).size());
    }

    @Test
    void getStockDaily_shouldSupportBatchTsCode() throws Exception {
        String response = tools.getStockDaily("000001.SZ|000002.SZ", "20240101", "20240131");
        Map<?, ?> root = objectMapper.readValue(response, Map.class);
        Map<?, ?> data = (Map<?, ?>) root.get("data");

        assertNotNull(data);
        assertEquals("batch", data.get("mode"));
        assertEquals(2, ((List<?>) data.get("results")).size());
    }
}
