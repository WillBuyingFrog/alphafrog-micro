package world.willfrog.alphafrogmicro.common.utils.compact;

import org.junit.Test;
import world.willfrog.alphafrogmicro.common.dto.compact.CompactApiResponse;
import world.willfrog.alphafrogmicro.common.dto.compact.CompactMeta;

import static org.junit.Assert.*;

/**
 * CompactJsonFormatter单元测试
 * 注意：由于需要实际的Proto消息，这里主要测试JSON解析功能
 */
public class CompactJsonFormatterTest {
    
    @Test
    public void testParseCompactJson() {
        String json = "{\"format\":\"compact\",\"fields\":[\"ts_code\",\"trade_date\",\"close\"],\"rows\":[[\"000001.SZ\",1640995200000,15.23],[\"000002.SZ\",1640995200000,18.45]],\"meta\":{\"total\":100,\"page\":1,\"page_size\":10}}";
        
        CompactApiResponse response = CompactJsonFormatter.parseCompactJson(json);
        
        assertNotNull(response);
        assertEquals("compact", response.getFormat());
        assertEquals(3, response.getFields().size());
        assertEquals("ts_code", response.getFields().get(0));
        assertEquals("trade_date", response.getFields().get(1));
        assertEquals("close", response.getFields().get(2));
        
        assertEquals(2, response.getRows().size());
        assertEquals(3, response.getRows().get(0).size());
        assertEquals("000001.SZ", response.getRows().get(0).get(0));
        assertEquals(1640995200000L, response.getRows().get(0).get(1));
        // JSON解析后数值可能为BigDecimal，使用doubleValue比较
        assertEquals(15.23, ((Number) response.getRows().get(0).get(2)).doubleValue(), 0.001);
        
        assertNotNull(response.getMeta());
        assertEquals(Long.valueOf(100), response.getMeta().getTotal());
        assertEquals(Integer.valueOf(1), response.getMeta().getPage());
        assertEquals(Integer.valueOf(10), response.getMeta().getPageSize());
    }
    
    @Test
    public void testIsValidCompactJson() {
        String validJson = "{\"format\":\"compact\",\"fields\":[\"f1\",\"f2\"],\"rows\":[[\"v1\",\"v2\"]]}";
        assertTrue(CompactJsonFormatter.isValidCompactJson(validJson));
        
        String invalidJson1 = "{\"format\":\"standard\",\"fields\":[\"f1\",\"f2\"],\"rows\":[[\"v1\",\"v2\"]]}";
        assertFalse(CompactJsonFormatter.isValidCompactJson(invalidJson1));
        
        String invalidJson2 = "{\"fields\":[\"f1\",\"f2\"],\"rows\":[[\"v1\",\"v2\"]]}";
        assertFalse(CompactJsonFormatter.isValidCompactJson(invalidJson2));
        
        String invalidJson3 = "invalid json";
        assertFalse(CompactJsonFormatter.isValidCompactJson(invalidJson3));
    }
    
    @Test
    public void testGetCompactJsonStats() {
        String json = "{\"format\":\"compact\",\"fields\":[\"f1\",\"f2\",\"f3\"],\"rows\":[[\"v1\",\"v2\",\"v3\"],[\"v4\",\"v5\",\"v6\"]],\"meta\":{\"total\":100}}";
        
        CompactJsonFormatter.CompactJsonStats stats = CompactJsonFormatter.getCompactJsonStats(json);
        
        assertNotNull(stats);
        assertEquals(3, stats.getFieldCount());
        assertEquals(2, stats.getRowCount());
        assertTrue(stats.isHasMeta());
        assertEquals(6, stats.getTotalElements()); // 3 fields * 2 rows
    }
    
    @Test
    public void testGetCompactJsonStatsWithInvalidJson() {
        String invalidJson = "invalid json";
        
        CompactJsonFormatter.CompactJsonStats stats = CompactJsonFormatter.getCompactJsonStats(invalidJson);
        
        assertNotNull(stats);
        assertEquals(0, stats.getFieldCount());
        assertEquals(0, stats.getRowCount());
        assertFalse(stats.isHasMeta());
        assertEquals(0, stats.getTotalElements());
    }
    
    @Test
    public void testCompactJsonStatsToString() {
        CompactJsonFormatter.CompactJsonStats stats = CompactJsonFormatter.CompactJsonStats.builder()
                .fieldCount(5)
                .rowCount(10)
                .hasMeta(true)
                .totalElements(50)
                .build();
        
        String statsString = stats.toString();
        assertTrue(statsString.contains("fields=5"));
        assertTrue(statsString.contains("rows=10"));
        assertTrue(statsString.contains("hasMeta=true"));
        assertTrue(statsString.contains("totalElements=50"));
    }
}