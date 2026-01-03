package world.willfrog.alphafrogmicro.common.utils.compact;

import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Proto消息字段提取器
 * 用于从Proto描述符中提取字段信息，支持字段别名和排序
 */
@Slf4j
public class ProtoFieldExtractor {
    
    /**
     * 字段信息
     */
    public static class FieldInfo {
        private final String name;
        private final String alias;
        private final Descriptors.FieldDescriptor.Type type;
        private final int index;
        private final boolean isRepeated;
        private final boolean isOptional;
        
        public FieldInfo(String name, String alias, Descriptors.FieldDescriptor.Type type, 
                        int index, boolean isRepeated, boolean isOptional) {
            this.name = name;
            this.alias = alias != null ? alias : name;
            this.type = type;
            this.index = index;
            this.isRepeated = isRepeated;
            this.isOptional = isOptional;
        }
        
        public String getName() { return name; }
        public String getAlias() { return alias; }
        public Descriptors.FieldDescriptor.Type getType() { return type; }
        public int getIndex() { return index; }
        public boolean isRepeated() { return isRepeated; }
        public boolean isOptional() { return isOptional; }
    }
    
    /**
     * 字段映射配置
     */
    public static class FieldMapping {
        private final Map<String, String> aliases = new HashMap<>();
        private final Set<String> excludes = new HashSet<>();
        private final List<String> orders = new ArrayList<>();
        
        public FieldMapping alias(String fieldName, String alias) {
            aliases.put(fieldName, alias);
            return this;
        }
        
        public FieldMapping exclude(String fieldName) {
            excludes.add(fieldName);
            return this;
        }
        
        public FieldMapping order(String... fieldNames) {
            orders.addAll(Arrays.asList(fieldNames));
            return this;
        }
        
        public Map<String, String> getAliases() { return aliases; }
        public Set<String> getExcludes() { return excludes; }
        public List<String> getOrders() { return orders; }
    }
    
    /**
     * 提取消息的所有字段信息
     */
    public static List<FieldInfo> extractFields(Message message) {
        return extractFields(message, new FieldMapping());
    }
    
    /**
     * 提取消息的所有字段信息，支持字段映射配置
     */
    public static List<FieldInfo> extractFields(Message message, FieldMapping mapping) {
        if (message == null) {
            return Collections.emptyList();
        }
        
        Descriptors.Descriptor descriptor = message.getDescriptorForType();
        List<Descriptors.FieldDescriptor> fieldDescriptors = descriptor.getFields();
        
        List<FieldInfo> fieldInfos = new ArrayList<>();
        
        for (Descriptors.FieldDescriptor fieldDescriptor : fieldDescriptors) {
            String fieldName = fieldDescriptor.getName();
            
            // 检查是否需要排除该字段
            if (mapping.getExcludes().contains(fieldName)) {
                continue;
            }
            
            // 获取字段别名
            String alias = mapping.getAliases().getOrDefault(fieldName, fieldName);
            
            FieldInfo fieldInfo = new FieldInfo(
                fieldName,
                alias,
                fieldDescriptor.getType(),
                fieldDescriptor.getIndex(),
                fieldDescriptor.isRepeated(),
                fieldDescriptor.isOptional()
            );
            
            fieldInfos.add(fieldInfo);
        }
        
        // 根据指定的顺序排序
        if (!mapping.getOrders().isEmpty()) {
            fieldInfos.sort((a, b) -> {
                int indexA = mapping.getOrders().indexOf(a.getName());
                int indexB = mapping.getOrders().indexOf(b.getName());
                
                // 如果都在排序列表中，按列表顺序
                if (indexA >= 0 && indexB >= 0) {
                    return Integer.compare(indexA, indexB);
                }
                
                // 如果只有a在排序列表中，a在前
                if (indexA >= 0) {
                    return -1;
                }
                
                // 如果只有b在排序列表中，b在前
                if (indexB >= 0) {
                    return 1;
                }
                
                // 都不在排序列表中，按原始顺序
                return Integer.compare(a.getIndex(), b.getIndex());
            });
        }
        
        return fieldInfos;
    }
    
    /**
     * 提取repeated字段中的消息类型字段信息
     * 用于处理响应中的items列表
     */
    public static List<FieldInfo> extractRepeatedMessageFields(Message message, String repeatedFieldName) {
        if (message == null || repeatedFieldName == null) {
            return Collections.emptyList();
        }
        
        Descriptors.Descriptor descriptor = message.getDescriptorForType();
        Descriptors.FieldDescriptor repeatedField = descriptor.findFieldByName(repeatedFieldName);
        
        if (repeatedField == null || !repeatedField.isRepeated()) {
            log.warn("Field {} not found or not repeated in message {}", repeatedFieldName, descriptor.getName());
            return Collections.emptyList();
        }
        
        // 获取repeated字段的元素类型
        Descriptors.FieldDescriptor.Type elementType = repeatedField.getType();
        
        if (elementType != Descriptors.FieldDescriptor.Type.MESSAGE) {
            log.warn("Field {} is not of MESSAGE type, actual type: {}", repeatedFieldName, elementType);
            return Collections.emptyList();
        }
        
        // 获取消息类型描述符
        Descriptors.Descriptor elementDescriptor = repeatedField.getMessageType();
        
        // 创建一个空的示例消息来获取字段信息
        try {
            // 使用DynamicMessage创建示例消息
            Message elementMessage = com.google.protobuf.DynamicMessage.newBuilder(elementDescriptor).build();
            return extractFields(elementMessage);
        } catch (Exception e) {
            log.error("Error creating element message for field {}", repeatedFieldName, e);
            return Collections.emptyList();
        }
    }
    
    /**
     * 获取字段值
     */
    public static Object getFieldValue(Message message, String fieldName) {
        if (message == null || fieldName == null) {
            return null;
        }
        
        Descriptors.FieldDescriptor field = message.getDescriptorForType().findFieldByName(fieldName);
        if (field == null) {
            log.warn("Field {} not found in message {}", fieldName, message.getDescriptorForType().getName());
            return null;
        }
        
        return message.getField(field);
    }
    
    /**
     * 获取repeated字段的值列表
     */
    @SuppressWarnings("unchecked")
    public static List<Message> getRepeatedFieldValues(Message message, String repeatedFieldName) {
        if (message == null || repeatedFieldName == null) {
            return Collections.emptyList();
        }
        
        Object fieldValue = getFieldValue(message, repeatedFieldName);
        if (fieldValue == null) {
            return Collections.emptyList();
        }
        
        if (!(fieldValue instanceof List)) {
            log.warn("Field {} is not a repeated field", repeatedFieldName);
            return Collections.emptyList();
        }
        
        return (List<Message>) fieldValue;
    }
    
    /**
     * 创建股票日线数据的字段映射配置
     */
    public static FieldMapping createStockDailyFieldMapping() {
        return new FieldMapping()
            .alias("tsCode", "ts_code")
            .alias("tradeDate", "trade_date")
            .alias("preClose", "pre_close")
            .alias("pctChg", "pct_chg")
            .order("tsCode", "tradeDate", "close", "open", "high", "low", 
                  "preClose", "change", "pctChg", "vol", "amount");
    }
    
    /**
     * 创建基金净值数据的字段映射配置
     */
    public static FieldMapping createFundNavFieldMapping() {
        return new FieldMapping()
            .alias("tsCode", "ts_code")
            .alias("annDate", "ann_date")
            .alias("navDate", "nav_date")
            .alias("unitNav", "unit_nav")
            .alias("accumNav", "accum_nav")
            .alias("accumDiv", "accum_div")
            .alias("netAsset", "net_asset")
            .alias("totalNetAsset", "total_net_asset")
            .alias("adjNav", "adj_nav")
            .order("tsCode", "navDate", "unitNav", "accumNav", "adjNav");
    }
    
    /**
     * 创建指数日线数据的字段映射配置
     */
    public static FieldMapping createIndexDailyFieldMapping() {
        return new FieldMapping()
            .alias("tsCode", "ts_code")
            .alias("tradeDate", "trade_date")
            .alias("preClose", "pre_close")
            .alias("pctChg", "pct_chg")
            .order("tsCode", "tradeDate", "close", "open", "high", "low", 
                  "preClose", "change", "pctChg", "vol", "amount");
    }
}