package world.willfrog.alphafrogmicro.common.utils.compact;

import com.google.protobuf.Message;
import lombok.extern.slf4j.Slf4j;
import world.willfrog.alphafrogmicro.common.dto.compact.CompactApiResponse;
import world.willfrog.alphafrogmicro.common.dto.compact.CompactMeta;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Proto消息到紧凑JSON格式的转换器
 * 提供proto消息列表转换为fields+rows格式的功能
 */
@Slf4j
public class CompactJsonConverter {
    
    /**
     * 将Proto消息转换为紧凑JSON响应
     */
    public static CompactApiResponse convert(Message message) {
        return convert(message, null, null);
    }
    
    /**
     * 将Proto消息转换为紧凑JSON响应，支持字段映射
     */
    public static CompactApiResponse convert(Message message, ProtoFieldExtractor.FieldMapping fieldMapping) {
        return convert(message, fieldMapping, null);
    }
    
    /**
     * 将Proto消息转换为紧凑JSON响应，支持字段映射和元数据
     */
    public static CompactApiResponse convert(Message message, 
                                           ProtoFieldExtractor.FieldMapping fieldMapping,
                                           CompactMeta meta) {
        if (message == null) {
            return CompactApiResponse.builder()
                    .format("compact")
                    .fields(Collections.emptyList())
                    .rows(Collections.emptyList())
                    .meta(meta)
                    .build();
        }
        
        try {
            // 提取repeated字段（通常是items）
            List<Message> items = extractItems(message);
            
            if (items.isEmpty()) {
                return CompactApiResponse.builder()
                        .format("compact")
                        .fields(Collections.emptyList())
                        .rows(Collections.emptyList())
                        .meta(meta)
                        .build();
            }
            
            // 提取字段信息
            Message firstItem = items.get(0);
            List<ProtoFieldExtractor.FieldInfo> fieldInfos = ProtoFieldExtractor.extractFields(firstItem, fieldMapping);
            
            // 构建字段名列表
            List<String> fields = fieldInfos.stream()
                    .map(ProtoFieldExtractor.FieldInfo::getAlias)
                    .collect(Collectors.toList());
            
            // 构建数据行
            List<List<Object>> rows = new ArrayList<>();
            for (Message item : items) {
                List<Object> row = new ArrayList<>();
                for (ProtoFieldExtractor.FieldInfo fieldInfo : fieldInfos) {
                    Object value = ProtoFieldExtractor.getFieldValue(item, fieldInfo.getName());
                    row.add(convertFieldValue(value, fieldInfo));
                }
                rows.add(row);
            }
            
            return CompactApiResponse.of(fields, rows, meta);
            
        } catch (Exception e) {
            log.error("Error converting proto message to compact format", e);
            throw new RuntimeException("Failed to convert proto message to compact format", e);
        }
    }
    
    /**
     * 转换股票日线响应
     */
    public static CompactApiResponse convertStockDaily(Message message) {
        return convert(message, ProtoFieldExtractor.createStockDailyFieldMapping());
    }
    
    /**
     * 转换股票日线响应，带元数据
     */
    public static CompactApiResponse convertStockDaily(Message message, CompactMeta meta) {
        return convert(message, ProtoFieldExtractor.createStockDailyFieldMapping(), meta);
    }
    
    /**
     * 转换基金净值响应
     */
    public static CompactApiResponse convertFundNav(Message message) {
        return convert(message, ProtoFieldExtractor.createFundNavFieldMapping());
    }
    
    /**
     * 转换基金净值响应，带元数据
     */
    public static CompactApiResponse convertFundNav(Message message, CompactMeta meta) {
        return convert(message, ProtoFieldExtractor.createFundNavFieldMapping(), meta);
    }
    
    /**
     * 转换指数日线响应
     */
    public static CompactApiResponse convertIndexDaily(Message message) {
        return convert(message, ProtoFieldExtractor.createIndexDailyFieldMapping());
    }
    
    /**
     * 转换指数日线响应，带元数据
     */
    public static CompactApiResponse convertIndexDaily(Message message, CompactMeta meta) {
        return convert(message, ProtoFieldExtractor.createIndexDailyFieldMapping(), meta);
    }
    
    /**
     * 从Proto消息中提取items列表
     */
    private static List<Message> extractItems(Message message) {
        if (message == null) {
            return Collections.emptyList();
        }
        
        // 尝试常见的repeated字段名
        String[] commonFieldNames = {"items", "data", "list", "results", "records"};
        
        for (String fieldName : commonFieldNames) {
            List<Message> items = ProtoFieldExtractor.getRepeatedFieldValues(message, fieldName);
            if (!items.isEmpty()) {
                log.debug("Found {} items in field '{}'", items.size(), fieldName);
                return items;
            }
        }
        
        // 如果没有找到repeated字段，尝试从描述符中查找第一个repeated MESSAGE字段
        try {
            for (com.google.protobuf.Descriptors.FieldDescriptor field : 
                 message.getDescriptorForType().getFields()) {
                if (field.isRepeated() && field.getType() == com.google.protobuf.Descriptors.FieldDescriptor.Type.MESSAGE) {
                    List<Message> items = ProtoFieldExtractor.getRepeatedFieldValues(message, field.getName());
                    if (!items.isEmpty()) {
                        log.debug("Found {} items in field '{}'", items.size(), field.getName());
                        return items;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error extracting items from message", e);
        }
        
        log.warn("No repeated message fields found in message {}", message.getDescriptorForType().getName());
        return Collections.emptyList();
    }
    
    /**
     * 转换字段值
     */
    private static Object convertFieldValue(Object value, ProtoFieldExtractor.FieldInfo fieldInfo) {
        if (value == null) {
            return null;
        }
        
        // 处理枚举类型
        if (value instanceof com.google.protobuf.ProtocolMessageEnum) {
            return ((com.google.protobuf.ProtocolMessageEnum) value).getNumber();
        }
        
        // 处理嵌套消息类型
        if (value instanceof Message) {
            // 对于嵌套消息，可以递归转换或者返回JSON字符串
            // 这里简化为返回toString()
            return value.toString();
        }
        
        // 处理集合类型
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            if (list.isEmpty()) {
                return Collections.emptyList();
            }
            
            // 如果是基本类型的列表，直接返回
            Object firstItem = list.get(0);
            if (isPrimitiveType(firstItem)) {
                return list;
            }
            
            // 如果是消息类型的列表，转换为字符串列表
            return list.stream()
                    .map(item -> item != null ? item.toString() : null)
                    .collect(Collectors.toList());
        }
        
        // 基本类型直接返回
        return value;
    }
    
    /**
     * 判断是否为基本类型
     */
    private static boolean isPrimitiveType(Object value) {
        if (value == null) {
            return false;
        }
        
        Class<?> clazz = value.getClass();
        return clazz.isPrimitive() ||
               clazz == String.class ||
               clazz == Integer.class ||
               clazz == Long.class ||
               clazz == Double.class ||
               clazz == Float.class ||
               clazz == Boolean.class ||
               clazz == Byte.class ||
               clazz == Short.class ||
               Number.class.isAssignableFrom(clazz);
    }
    
    /**
     * 从Proto响应中提取元数据
     */
    public static CompactMeta extractMetaFromResponse(Message message) {
        if (message == null) {
            return null;
        }
        
        CompactMeta.CompactMetaBuilder metaBuilder = CompactMeta.builder();
        
        try {
            // 提取常见字段
            if (hasField(message, "tsCode")) {
                extractStringField(message, "tsCode", metaBuilder::tsCode);
            }
            if (hasField(message, "startDate")) {
                extractLongField(message, "startDate", metaBuilder::startDate);
            }
            if (hasField(message, "endDate")) {
                extractLongField(message, "endDate", metaBuilder::endDate);
            }
            if (hasField(message, "expectedTradingDays")) {
                extractIntegerField(message, "expectedTradingDays", metaBuilder::expectedTradingDays);
            }
            if (hasField(message, "actualTradingDays")) {
                extractIntegerField(message, "actualTradingDays", metaBuilder::actualTradingDays);
            }
            if (hasField(message, "missingCount")) {
                extractIntegerField(message, "missingCount", metaBuilder::missingCount);
            }
            if (hasField(message, "complete")) {
                extractBooleanField(message, "complete", metaBuilder::complete);
            }
            if (hasField(message, "upstreamGap")) {
                extractBooleanField(message, "upstreamGap", metaBuilder::upstreamGap);
            }
            if (hasField(message, "fromCache")) {
                extractBooleanField(message, "fromCache", metaBuilder::fromCache);
            }
            if (hasField(message, "status")) {
                extractStringField(message, "status", metaBuilder::status);
            }
            
        } catch (Exception e) {
            log.warn("Error extracting meta from response", e);
        }
        
        return metaBuilder.build();
    }
    
    /**
     * 从Proto响应中提取分页元数据
     */
    public static CompactMeta extractPageMetaFromResponse(Message message, int page, int pageSize) {
        CompactMeta meta = extractMetaFromResponse(message);
        if (meta == null) {
            meta = CompactMeta.builder().build();
        }
        
        // 设置分页信息
        meta.setPage(page);
        meta.setPageSize(pageSize);
        
        // 尝试从响应中提取总记录数
        try {
            if (hasField(message, "total")) {
                Object totalValue = ProtoFieldExtractor.getFieldValue(message, "total");
                if (totalValue instanceof Number) {
                    meta.setTotal(((Number) totalValue).longValue());
                }
            }
        } catch (Exception e) {
            log.debug("Could not extract total count from response");
        }
        
        return meta;
    }

    private static boolean hasField(Message message, String fieldName) {
        return message != null
                && fieldName != null
                && message.getDescriptorForType().findFieldByName(fieldName) != null;
    }
    
    private static void extractStringField(Message message, String fieldName, java.util.function.Consumer<String> setter) {
        try {
            Object value = ProtoFieldExtractor.getFieldValue(message, fieldName);
            if (value instanceof String) {
                setter.accept((String) value);
            }
        } catch (Exception e) {
            log.debug("Could not extract field {}: {}", fieldName, e.getMessage());
        }
    }
    
    private static void extractLongField(Message message, String fieldName, java.util.function.Consumer<Long> setter) {
        try {
            Object value = ProtoFieldExtractor.getFieldValue(message, fieldName);
            if (value instanceof Number) {
                setter.accept(((Number) value).longValue());
            }
        } catch (Exception e) {
            log.debug("Could not extract field {}: {}", fieldName, e.getMessage());
        }
    }
    
    private static void extractIntegerField(Message message, String fieldName, java.util.function.Consumer<Integer> setter) {
        try {
            Object value = ProtoFieldExtractor.getFieldValue(message, fieldName);
            if (value instanceof Number) {
                setter.accept(((Number) value).intValue());
            }
        } catch (Exception e) {
            log.debug("Could not extract field {}: {}", fieldName, e.getMessage());
        }
    }
    
    private static void extractBooleanField(Message message, String fieldName, java.util.function.Consumer<Boolean> setter) {
        try {
            Object value = ProtoFieldExtractor.getFieldValue(message, fieldName);
            if (value instanceof Boolean) {
                setter.accept((Boolean) value);
            }
        } catch (Exception e) {
            log.debug("Could not extract field {}: {}", fieldName, e.getMessage());
        }
    }
}
