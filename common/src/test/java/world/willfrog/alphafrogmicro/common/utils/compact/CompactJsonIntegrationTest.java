package world.willfrog.alphafrogmicro.common.utils.compact;

import org.junit.Test;
import world.willfrog.alphafrogmicro.common.dto.compact.CompactApiResponse;
import world.willfrog.alphafrogmicro.common.dto.compact.CompactMeta;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 紧凑JSON集成测试
 * 演示如何使用CompactJson工具类
 */
public class CompactJsonIntegrationTest {
    
    @Test
    public void testCompleteWorkflow() {
        // 1. 创建模拟数据（在实际应用中，这些数据来自Proto消息）
        List<String> fields = Arrays.asList("ts_code", "trade_date", "close", "open", "high", "low", "vol", "amount");
        List<List<Object>> rows = Arrays.asList(
            Arrays.asList("000001.SZ", 1640995200000L, 15.23, 15.10, 15.35, 15.05, 1234567.89, 123456789.12),
            Arrays.asList("000002.SZ", 1640995200000L, 18.45, 18.20, 18.60, 18.15, 2345678.90, 234567890.34),
            Arrays.asList("000003.SZ", 1640995200000L, 12.67, 12.50, 12.80, 12.40, 3456789.01, 345678901.56)
        );
        
        // 2. 创建元数据
        CompactMeta meta = CompactMeta.builder()
                .tsCode("000001.SZ,000002.SZ,000003.SZ")
                .startDate(1640995200000L)
                .endDate(1640995200000L)
                .expectedTradingDays(1)
                .actualTradingDays(1)
                .missingCount(0)
                .complete(true)
                .upstreamGap(false)
                .fromCache(true)
                .page(1)
                .pageSize(10)
                .total(3L)
                .status("success")
                .build();
        
        // 3. 创建紧凑响应
        CompactApiResponse response = CompactApiResponse.of(fields, rows, meta);
        
        // 4. 验证响应格式
        assertNotNull(response);
        assertEquals("compact", response.getFormat());
        assertEquals(8, response.getFields().size());
        assertEquals(3, response.getRows().size());
        assertNotNull(response.getMeta());
        
        // 5. 验证数据完整性
        assertEquals("ts_code", response.getFields().get(0));
        assertEquals("trade_date", response.getFields().get(1));
        assertEquals("close", response.getFields().get(2));
        
        List<Object> firstRow = response.getRows().get(0);
        assertEquals("000001.SZ", firstRow.get(0));
        assertEquals(1640995200000L, firstRow.get(1));
        assertEquals(15.23, firstRow.get(2));
        
        // 6. 验证元数据
        CompactMeta responseMeta = response.getMeta();
        assertEquals("000001.SZ,000002.SZ,000003.SZ", responseMeta.getTsCode());
        assertEquals(Long.valueOf(1640995200000L), responseMeta.getStartDate());
        assertEquals(Long.valueOf(1640995200000L), responseMeta.getEndDate());
        assertEquals(Integer.valueOf(1), responseMeta.getExpectedTradingDays());
        assertEquals(Integer.valueOf(1), responseMeta.getActualTradingDays());
        assertEquals(Integer.valueOf(0), responseMeta.getMissingCount());
        assertEquals(Boolean.TRUE, responseMeta.getComplete());
        assertEquals(Boolean.FALSE, responseMeta.getUpstreamGap());
        assertEquals(Boolean.TRUE, responseMeta.getFromCache());
        assertEquals(Integer.valueOf(1), responseMeta.getPage());
        assertEquals(Integer.valueOf(10), responseMeta.getPageSize());
        assertEquals(Long.valueOf(3L), responseMeta.getTotal());
        assertEquals("success", responseMeta.getStatus());
    }
    
    @Test
    public void testFieldMappingWorkflow() {
        // 1. 创建字段映射配置
        ProtoFieldExtractor.FieldMapping mapping = new ProtoFieldExtractor.FieldMapping()
                .alias("tsCode", "ts_code")
                .alias("tradeDate", "trade_date")
                .alias("preClose", "pre_close")
                .alias("pctChg", "pct_chg")
                .order("tsCode", "tradeDate", "close", "open", "high", "low", 
                      "preClose", "change", "pctChg", "vol", "amount");
        
        // 2. 验证映射配置
        assertEquals("ts_code", mapping.getAliases().get("tsCode"));
        assertEquals("trade_date", mapping.getAliases().get("tradeDate"));
        assertEquals("pre_close", mapping.getAliases().get("preClose"));
        assertEquals("pct_chg", mapping.getAliases().get("pctChg"));
        
        List<String> order = mapping.getOrders();
        assertEquals("tsCode", order.get(0));
        assertEquals("tradeDate", order.get(1));
        assertEquals("close", order.get(2));
    }
    
    @Test
    public void testCompactMetaBuilders() {
        // 1. 基础元数据
        CompactMeta basicMeta = CompactMeta.basic();
        assertNotNull(basicMeta);
        
        // 2. 分页元数据
        CompactMeta pageMeta = CompactMeta.page(2, 20, 100L);
        assertNotNull(pageMeta);
        assertEquals(Integer.valueOf(2), pageMeta.getPage());
        assertEquals(Integer.valueOf(20), pageMeta.getPageSize());
        assertEquals(Long.valueOf(100L), pageMeta.getTotal());
        
        // 3. 完整性元数据
        CompactMeta completenessMeta = CompactMeta.completeness(
                "000001.SZ", 
                1640995200000L, 
                1641081600000L,
                5, 
                4, 
                1, 
                false
        );
        assertNotNull(completenessMeta);
        assertEquals("000001.SZ", completenessMeta.getTsCode());
        assertEquals(Long.valueOf(1640995200000L), completenessMeta.getStartDate());
        assertEquals(Long.valueOf(1641081600000L), completenessMeta.getEndDate());
        assertEquals(Integer.valueOf(5), completenessMeta.getExpectedTradingDays());
        assertEquals(Integer.valueOf(4), completenessMeta.getActualTradingDays());
        assertEquals(Integer.valueOf(1), completenessMeta.getMissingCount());
        assertEquals(Boolean.FALSE, completenessMeta.getComplete());
    }
    
    @Test
    public void testSizeComparison() {
        // 模拟传统JSON格式的大小
        String traditionalJson = "{\"items\":[{\"ts_code\":\"000001.SZ\",\"trade_date\":1640995200000,\"close\":15.23,\"open\":15.10,\"high\":15.35,\"low\":15.05,\"vol\":1234567.89,\"amount\":123456789.12},{\"ts_code\":\"000002.SZ\",\"trade_date\":1640995200000,\"close\":18.45,\"open\":18.20,\"high\":18.60,\"low\":18.15,\"vol\":2345678.90,\"amount\":234567890.34}]}";
        
        // 模拟紧凑JSON格式
        String compactJson = "{\"format\":\"compact\",\"fields\":[\"ts_code\",\"trade_date\",\"close\",\"open\",\"high\",\"low\",\"vol\",\"amount\"],\"rows\":[[\"000001.SZ\",1640995200000,15.23,15.10,15.35,15.05,1234567.89,123456789.12],[\"000002.SZ\",1640995200000,18.45,18.20,18.60,18.15,2345678.90,234567890.34]]}";
        
        // 计算大小差异
        int traditionalSize = traditionalJson.length();
        int compactSize = compactJson.length();
        double reduction = (1.0 - (double) compactSize / traditionalSize) * 100;
        
        System.out.println("Traditional JSON size: " + traditionalSize + " bytes");
        System.out.println("Compact JSON size: " + compactSize + " bytes");
        System.out.println("Size reduction: " + String.format("%.1f%%", reduction));
        
        // 验证紧凑格式确实更小
        assertTrue("Compact format should be smaller", compactSize < traditionalSize);
        
        // 验证大小减少比例符合预期（至少10%的减少，对于小数据集）
        assertTrue("Size reduction should be at least 10%", reduction > 10.0);
    }
}