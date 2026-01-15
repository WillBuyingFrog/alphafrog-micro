package world.willfrog.alphafrogmicro.common.dto.compact;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 紧凑JSON响应格式DTO
 * 用于优化API响应大小，提升传输效率
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompactApiResponse {
    
    /**
     * 格式标识，固定为"compact"
     */
    @Builder.Default
    private String format = "compact";
    
    /**
     * 字段名数组
     * 定义了rows中每列的字段名称
     */
    private List<String> fields;
    
    /**
     * 数据行数组
     * 每行是一个对象数组，按fields定义的顺序排列
     */
    private List<List<Object>> rows;
    
    /**
     * 元数据（可选）
     * 包含分页、状态、统计等信息
     */
    private CompactMeta meta;
    
    /**
     * 创建紧凑响应的便捷方法
     */
    public static CompactApiResponse of(List<String> fields, List<List<Object>> rows) {
        return CompactApiResponse.builder()
                .format("compact")
                .fields(fields)
                .rows(rows)
                .build();
    }
    
    /**
     * 创建带元数据的紧凑响应
     */
    public static CompactApiResponse of(List<String> fields, List<List<Object>> rows, CompactMeta meta) {
        return CompactApiResponse.builder()
                .format("compact")
                .fields(fields)
                .rows(rows)
                .meta(meta)
                .build();
    }
}