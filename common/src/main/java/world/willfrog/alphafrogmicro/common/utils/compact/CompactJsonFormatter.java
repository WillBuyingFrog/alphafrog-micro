package world.willfrog.alphafrogmicro.common.utils.compact;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.protobuf.Message;
import lombok.extern.slf4j.Slf4j;
import world.willfrog.alphafrogmicro.common.dto.compact.CompactApiResponse;
import world.willfrog.alphafrogmicro.common.dto.compact.CompactMeta;

/**
 * 紧凑JSON格式化工具类
 * 提供Proto消息到紧凑JSON字符串的直接转换
 */
@Slf4j
public class CompactJsonFormatter {
    
    /**
     * 将Proto消息转换为紧凑JSON字符串
     */
    public static String toCompactJson(Message message) {
        return toCompactJson(message, null, null);
    }
    
    /**
     * 将Proto消息转换为紧凑JSON字符串，支持字段映射
     */
    public static String toCompactJson(Message message, ProtoFieldExtractor.FieldMapping fieldMapping) {
        return toCompactJson(message, fieldMapping, null);
    }
    
    /**
     * 将Proto消息转换为紧凑JSON字符串，支持字段映射和元数据
     */
    public static String toCompactJson(Message message, 
                                     ProtoFieldExtractor.FieldMapping fieldMapping,
                                     CompactMeta meta) {
        try {
            CompactApiResponse response = CompactJsonConverter.convert(message, fieldMapping, meta);
            return JSON.toJSONString(response);
        } catch (Exception e) {
            log.error("Error converting proto message to compact JSON", e);
            throw new RuntimeException("Failed to convert proto message to compact JSON", e);
        }
    }
    
    /**
     * 将股票日线Proto消息转换为紧凑JSON字符串
     */
    public static String toCompactJsonStockDaily(Message message) {
        return toCompactJsonStockDaily(message, null);
    }
    
    /**
     * 将股票日线Proto消息转换为紧凑JSON字符串，带元数据
     */
    public static String toCompactJsonStockDaily(Message message, CompactMeta meta) {
        try {
            CompactApiResponse response = CompactJsonConverter.convertStockDaily(message, meta);
            return JSON.toJSONString(response);
        } catch (Exception e) {
            log.error("Error converting stock daily proto message to compact JSON", e);
            throw new RuntimeException("Failed to convert stock daily proto message to compact JSON", e);
        }
    }
    
    /**
     * 将基金净值Proto消息转换为紧凑JSON字符串
     */
    public static String toCompactJsonFundNav(Message message) {
        return toCompactJsonFundNav(message, null);
    }
    
    /**
     * 将基金净值Proto消息转换为紧凑JSON字符串，带元数据
     */
    public static String toCompactJsonFundNav(Message message, CompactMeta meta) {
        try {
            CompactApiResponse response = CompactJsonConverter.convertFundNav(message, meta);
            return JSON.toJSONString(response);
        } catch (Exception e) {
            log.error("Error converting fund nav proto message to compact JSON", e);
            throw new RuntimeException("Failed to convert fund nav proto message to compact JSON", e);
        }
    }
    
    /**
     * 将指数日线Proto消息转换为紧凑JSON字符串
     */
    public static String toCompactJsonIndexDaily(Message message) {
        return toCompactJsonIndexDaily(message, null);
    }
    
    /**
     * 将指数日线Proto消息转换为紧凑JSON字符串，带元数据
     */
    public static String toCompactJsonIndexDaily(Message message, CompactMeta meta) {
        try {
            CompactApiResponse response = CompactJsonConverter.convertIndexDaily(message, meta);
            return JSON.toJSONString(response);
        } catch (Exception e) {
            log.error("Error converting index daily proto message to compact JSON", e);
            throw new RuntimeException("Failed to convert index daily proto message to compact JSON", e);
        }
    }
    
    /**
     * 从Proto响应中提取元数据并转换为紧凑JSON字符串
     */
    public static String toCompactJsonWithExtractedMeta(Message message) {
        CompactMeta meta = CompactJsonConverter.extractMetaFromResponse(message);
        return toCompactJson(message, null, meta);
    }
    
    /**
     * 从Proto响应中提取分页元数据并转换为紧凑JSON字符串
     */
    public static String toCompactJsonWithExtractedPageMeta(Message message, int page, int pageSize) {
        CompactMeta meta = CompactJsonConverter.extractPageMetaFromResponse(message, page, pageSize);
        return toCompactJson(message, null, meta);
    }
    
    /**
     * 解析紧凑JSON字符串为CompactApiResponse对象
     */
    public static CompactApiResponse parseCompactJson(String json) {
        try {
            return JSON.parseObject(json, CompactApiResponse.class);
        } catch (Exception e) {
            log.error("Error parsing compact JSON string", e);
            throw new RuntimeException("Failed to parse compact JSON string", e);
        }
    }
    
    /**
     * 验证紧凑JSON格式
     */
    public static boolean isValidCompactJson(String json) {
        try {
            JSONObject jsonObject = JSON.parseObject(json);
            return "compact".equals(jsonObject.getString("format")) &&
                   jsonObject.containsKey("fields") &&
                   jsonObject.containsKey("rows");
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 获取紧凑JSON的统计信息
     */
    public static CompactJsonStats getCompactJsonStats(String json) {
        try {
            CompactApiResponse response = parseCompactJson(json);
            return CompactJsonStats.builder()
                    .fieldCount(response.getFields() != null ? response.getFields().size() : 0)
                    .rowCount(response.getRows() != null ? response.getRows().size() : 0)
                    .hasMeta(response.getMeta() != null)
                    .totalElements((response.getFields() != null ? response.getFields().size() : 0) * 
                                  (response.getRows() != null ? response.getRows().size() : 0))
                    .build();
        } catch (Exception e) {
            log.error("Error getting compact JSON stats", e);
            return CompactJsonStats.builder()
                    .fieldCount(0)
                    .rowCount(0)
                    .hasMeta(false)
                    .totalElements(0)
                    .build();
        }
    }
    
    /**
     * 紧凑JSON统计信息
     */
    public static class CompactJsonStats {
        private final int fieldCount;
        private final int rowCount;
        private final boolean hasMeta;
        private final int totalElements;
        
        private CompactJsonStats(Builder builder) {
            this.fieldCount = builder.fieldCount;
            this.rowCount = builder.rowCount;
            this.hasMeta = builder.hasMeta;
            this.totalElements = builder.totalElements;
        }
        
        public int getFieldCount() { return fieldCount; }
        public int getRowCount() { return rowCount; }
        public boolean isHasMeta() { return hasMeta; }
        public int getTotalElements() { return totalElements; }
        
        @Override
        public String toString() {
            return String.format("CompactJsonStats{fields=%d, rows=%d, hasMeta=%s, totalElements=%d}",
                    fieldCount, rowCount, hasMeta, totalElements);
        }
        
        public static Builder builder() {
            return new Builder();
        }
        
        public static class Builder {
            private int fieldCount;
            private int rowCount;
            private boolean hasMeta;
            private int totalElements;
            
            public Builder fieldCount(int fieldCount) {
                this.fieldCount = fieldCount;
                return this;
            }
            
            public Builder rowCount(int rowCount) {
                this.rowCount = rowCount;
                return this;
            }
            
            public Builder hasMeta(boolean hasMeta) {
                this.hasMeta = hasMeta;
                return this;
            }
            
            public Builder totalElements(int totalElements) {
                this.totalElements = totalElements;
                return this;
            }
            
            public CompactJsonStats build() {
                return new CompactJsonStats(this);
            }
        }
    }
}