package world.willfrog.alphafrogmicro.common.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 统一响应状态码枚举
 */
@Getter
@AllArgsConstructor
public enum ResponseCode {
    
    /**
     * 成功响应
     */
    SUCCESS("200", "成功"),
    
    /**
     * 参数错误
     */
    PARAM_ERROR("400", "参数错误"),
    
    /**
     * 未授权
     */
    UNAUTHORIZED("401", "未授权"),
    
    /**
     * 无权限
     */
    FORBIDDEN("403", "无权限"),
    
    /**
     * 数据未找到
     */
    DATA_NOT_FOUND("404", "数据未找到"),
    
    /**
     * 数据已存在
     */
    DATA_EXIST("409", "数据已存在"),
    
    /**
     * 业务处理异常
     */
    BUSINESS_ERROR("422", "业务处理异常"),
    
    /**
     * 系统内部错误
     */
    SYSTEM_ERROR("500", "系统内部错误"),
    
    /**
     * 服务不可用
     */
    SERVICE_UNAVAILABLE("503", "服务不可用"),
    
    /**
     * 数据库操作错误
     */
    DATABASE_ERROR("510", "数据库操作错误"),
    
    /**
     * 外部服务调用错误
     */
    EXTERNAL_SERVICE_ERROR("520", "外部服务调用错误"),
    
    /**
     * 数据转换错误
     */
    DATA_CONVERT_ERROR("530", "数据转换错误");
    
    /**
     * 状态码
     */
    private final String code;
    
    /**
     * 状态消息
     */
    private final String message;
    
    /**
     * 获取状态码
     */
    public String getCode() {
        return code;
    }
    
    /**
     * 获取状态消息
     */
    public String getMessage() {
        return message;
    }
    
    /**
     * 通过状态码获取枚举
     */
    public static ResponseCode getByCode(String code) {
        for (ResponseCode responseCode : values()) {
            if (responseCode.getCode().equals(code)) {
                return responseCode;
            }
        }
        return SYSTEM_ERROR;
    }
}