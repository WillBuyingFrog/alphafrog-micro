package world.willfrog.alphafrogmicro.common.utils.compact;

import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import org.junit.Test;
import world.willfrog.alphafrogmicro.common.utils.compact.ProtoFieldExtractor.FieldInfo;

import java.util.List;

import static org.junit.Assert.*;

/**
 * ProtoFieldExtractor单元测试
 * 注意：由于需要实际的Proto消息，这里主要测试工具类的基本功能
 */
public class ProtoFieldExtractorTest {
    
    @Test
    public void testFieldMapping() {
        ProtoFieldExtractor.FieldMapping mapping = new ProtoFieldExtractor.FieldMapping()
                .alias("tsCode", "ts_code")
                .alias("tradeDate", "trade_date")
                .exclude("internalField")
                .order("tsCode", "tradeDate", "close");
        
        assertEquals("ts_code", mapping.getAliases().get("tsCode"));
        assertEquals("trade_date", mapping.getAliases().get("tradeDate"));
        assertTrue(mapping.getExcludes().contains("internalField"));
        assertEquals(3, mapping.getOrders().size());
        assertEquals("tsCode", mapping.getOrders().get(0));
    }
    
    @Test
    public void testFieldMappingChaining() {
        ProtoFieldExtractor.FieldMapping mapping = new ProtoFieldExtractor.FieldMapping()
                .alias("field1", "f1")
                .exclude("field2")
                .order("field3", "field4");
        
        assertEquals(1, mapping.getAliases().size());
        assertEquals(1, mapping.getExcludes().size());
        assertEquals(2, mapping.getOrders().size());
    }
    
    @Test
    public void testPredefinedFieldMappings() {
        // 测试股票日线字段映射
        ProtoFieldExtractor.FieldMapping stockMapping = ProtoFieldExtractor.createStockDailyFieldMapping();
        assertNotNull(stockMapping);
        assertEquals("ts_code", stockMapping.getAliases().get("tsCode"));
        assertEquals("trade_date", stockMapping.getAliases().get("tradeDate"));
        assertEquals("pre_close", stockMapping.getAliases().get("preClose"));
        assertEquals("pct_chg", stockMapping.getAliases().get("pctChg"));
        
        // 测试基金净值字段映射
        ProtoFieldExtractor.FieldMapping fundMapping = ProtoFieldExtractor.createFundNavFieldMapping();
        assertNotNull(fundMapping);
        assertEquals("ts_code", fundMapping.getAliases().get("tsCode"));
        assertEquals("nav_date", fundMapping.getAliases().get("navDate"));
        assertEquals("unit_nav", fundMapping.getAliases().get("unitNav"));
        
        // 测试指数日线字段映射
        ProtoFieldExtractor.FieldMapping indexMapping = ProtoFieldExtractor.createIndexDailyFieldMapping();
        assertNotNull(indexMapping);
        assertEquals("ts_code", indexMapping.getAliases().get("tsCode"));
        assertEquals("trade_date", indexMapping.getAliases().get("tradeDate"));
    }
    
    @Test
    public void testExtractFieldsWithNullMessage() {
        List<FieldInfo> fields = ProtoFieldExtractor.extractFields(null);
        assertNotNull(fields);
        assertTrue(fields.isEmpty());
    }
    
    @Test
    public void testGetFieldValueWithNullMessage() {
        Object value = ProtoFieldExtractor.getFieldValue(null, "fieldName");
        assertNull(value);
    }
    
    @Test
    public void testGetRepeatedFieldValuesWithNullMessage() {
        List<Message> values = ProtoFieldExtractor.getRepeatedFieldValues(null, "fieldName");
        assertNotNull(values);
        assertTrue(values.isEmpty());
    }
}