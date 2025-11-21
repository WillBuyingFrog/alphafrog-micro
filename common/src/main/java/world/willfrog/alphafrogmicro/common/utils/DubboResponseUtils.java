package world.willfrog.alphafrogmicro.common.utils;

import lombok.extern.slf4j.Slf4j;
import world.willfrog.alphafrogmicro.common.dto.ResponseCode;

/**
 * Dubbo服务响应工具类
 * 用于处理Dubbo服务中的异常情况和null返回问题
 */
@Slf4j
public class DubboResponseUtils {
    
    /**
     * 处理Dubbo服务异常，记录日志并返回null
     * 用于兼容现有的Dubbo接口定义
     * 
     * @param e 异常
     * @param serviceName 服务名称
     * @param methodName 方法名称
     * @param params 参数信息
     * @return null
     */
    public static <T> T handleException(Exception e, String serviceName, String methodName, Object... params) {
        log.error("Dubbo服务调用异常 - 服务: {}, 方法: {}, 参数: {}", serviceName, methodName, params, e);
        return null;
    }
    
    /**
     * 处理Dubbo服务异常，记录日志并返回空响应对象
     * 用于支持新的一致性错误处理机制
     * 
     * @param e 异常
     * @param serviceName 服务名称
     * @param methodName 方法名称
     * @param emptyResponse 空响应对象
     * @param params 参数信息
     * @return 空响应对象
     */
    public static <T> T handleExceptionWithEmptyResponse(Exception e, String serviceName, String methodName, T emptyResponse, Object... params) {
        log.error("Dubbo服务调用异常 - 服务: {}, 方法: {}, 参数: {}, 错误码: {}", 
                 serviceName, methodName, params, ResponseCode.SYSTEM_ERROR.getCode(), e);
        return emptyResponse;
    }
    
    /**
     * 处理数据未找到的情况
     * 
     * @param serviceName 服务名称
     * @param methodName 方法名称
     * @param params 参数信息
     * @return null
     */
    public static <T> T handleDataNotFound(String serviceName, String methodName, Object... params) {
        log.warn("数据未找到 - 服务: {}, 方法: {}, 参数: {}", serviceName, methodName, params);
        return null;
    }
    
    /**
     * 处理数据未找到的情况，返回空响应对象
     * 
     * @param serviceName 服务名称
     * @param methodName 方法名称
     * @param emptyResponse 空响应对象
     * @param params 参数信息
     * @return 空响应对象
     */
    public static <T> T handleDataNotFoundWithEmptyResponse(String serviceName, String methodName, T emptyResponse, Object... params) {
        log.warn("数据未找到 - 服务: {}, 方法: {}, 参数: {}", serviceName, methodName, params);
        return emptyResponse;
    }
    
    /**
     * 记录数据转换错误
     * 
     * @param e 异常
     * @param serviceName 服务名称
     * @param methodName 方法名称
     * @param params 参数信息
     */
    public static void logConvertError(Exception e, String serviceName, String methodName, Object... params) {
        log.error("数据转换错误 - 服务: {}, 方法: {}, 参数: {}", serviceName, methodName, params, e);
    }
    
    /**
     * 记录数据库操作错误
     * 
     * @param e 异常
     * @param serviceName 服务名称
     * @param methodName 方法名称
     * @param params 参数信息
     */
    public static void logDatabaseError(Exception e, String serviceName, String methodName, Object... params) {
        log.error("数据库操作错误 - 服务: {}, 方法: {}, 参数: {}", serviceName, methodName, params, e);
    }
    
    /**
     * 构建错误响应消息
     * 
     * @param responseCode 响应码
     * @param detail 详细信息
     * @return 完整的错误消息
     */
    public static String buildErrorMessage(ResponseCode responseCode, String detail) {
        return String.format("[%s] %s: %s", responseCode.getCode(), responseCode.getMessage(), detail);
    }
    
    /**
     * 构建参数错误消息
     * 
     * @param paramName 参数名称
     * @param detail 详细信息
     * @return 完整的参数错误消息
     */
    public static String buildParamErrorMessage(String paramName, String detail) {
        return buildErrorMessage(ResponseCode.PARAM_ERROR, String.format("参数[%s] %s", paramName, detail));
    }
}