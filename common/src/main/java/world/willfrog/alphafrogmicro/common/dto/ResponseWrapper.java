package world.willfrog.alphafrogmicro.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 统一响应包装类
 * @param <T> 响应数据类型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResponseWrapper<T> implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * 响应状态码
     */
    private Integer code;
    
    /**
     * 响应消息
     */
    private String message;
    
    /**
     * 响应数据
     */
    private T data;
    
    /**
     * 时间戳
     */
    private long timestamp;
    
    /**
     * 请求ID，用于链路追踪
     */
    private String requestId;
    
    /**
     * 成功响应
     */
    public static <T> ResponseWrapper<T> success(T data) {
        return ResponseWrapper.<T>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .message(ResponseCode.SUCCESS.getMessage())
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
    }
    
    /**
     * 成功响应，自定义消息
     */
    public static <T> ResponseWrapper<T> success(T data, String message) {
        return ResponseWrapper.<T>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .message(message)
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
    }
    
    /**
     * 错误响应
     */
    public static <T> ResponseWrapper<T> error(ResponseCode responseCode) {
        return ResponseWrapper.<T>builder()
                .code(responseCode.getCode())
                .message(responseCode.getMessage())
                .timestamp(System.currentTimeMillis())
                .build();
    }
    
    /**
     * 错误响应，自定义消息
     */
    public static <T> ResponseWrapper<T> error(ResponseCode responseCode, String message) {
        return ResponseWrapper.<T>builder()
                .code(responseCode.getCode())
                .message(message)
                .timestamp(System.currentTimeMillis())
                .build();
    }
    
    /**
     * 错误响应，异常信息
     */
    public static <T> ResponseWrapper<T> error(ResponseCode responseCode, Exception e) {
        return ResponseWrapper.<T>builder()
                .code(responseCode.getCode())
                .message(String.format("%s: %s", responseCode.getMessage(), e.getMessage()))
                .timestamp(System.currentTimeMillis())
                .build();
    }
    
    /**
     * 系统错误响应
     */
    public static <T> ResponseWrapper<T> systemError(String message) {
        return ResponseWrapper.<T>builder()
                .code(ResponseCode.SYSTEM_ERROR.getCode())
                .message(message)
                .timestamp(System.currentTimeMillis())
                .build();
    }
    
    /**
     * 数据未找到响应
     */
    public static <T> ResponseWrapper<T> notFound(String message) {
        return ResponseWrapper.<T>builder()
                .code(ResponseCode.DATA_NOT_FOUND.getCode())
                .message(message)
                .timestamp(System.currentTimeMillis())
                .build();
    }
    
    /**
     * 参数错误响应
     */
    public static <T> ResponseWrapper<T> paramError(String message) {
        return ResponseWrapper.<T>builder()
                .code(ResponseCode.PARAM_ERROR.getCode())
                .message(message)
                .timestamp(System.currentTimeMillis())
                .build();
    }
    
    /**
     * 判断是否成功
     */
    public boolean isSuccess() {
        return ResponseCode.SUCCESS.getCode().equals(this.code);
    }
}