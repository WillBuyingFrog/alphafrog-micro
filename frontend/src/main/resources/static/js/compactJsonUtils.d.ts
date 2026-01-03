/**
 * AlphaFrog 紧凑JSON格式解析工具 - TypeScript类型定义
 * 
 * @author AlphaFrog Team
 * @version 1.0.0
 */

/**
 * 紧凑JSON响应格式
 */
export interface CompactJsonResponse {
    /** 格式标识，固定为"compact" */
    format: string;
    /** 字段名数组 */
    fields: string[];
    /** 数据行数组 */
    rows: any[][];
    /** 元数据信息（可选） */
    meta?: Record<string, any>;
}

/**
 * 解析结果
 */
export interface ParseResult {
    /** 解析后的对象数组 */
    data: Record<string, any>[];
    /** 元数据信息（如果原始响应包含） */
    meta?: Record<string, any> | null;
}

/**
 * 缓存统计信息
 */
export interface CacheStats {
    /** 字段映射器缓存大小 */
    fieldMapperCacheSize: number;
    /** 类型转换器缓存大小 */
    typeConverterCacheSize: number;
}

/**
 * 字段映射器函数类型
 */
export type FieldMapper = (row: any[]) => Record<string, any>;

/**
 * 将紧凑JSON解析为对象数组
 * @param response - 紧凑JSON响应
 * @returns 解析后的对象数组
 * @throws 当响应格式无效时抛出错误
 */
export declare function parseCompactJson(response: CompactJsonResponse): Record<string, any>[];

/**
 * 将紧凑JSON解析为包含数据和元数据的对象
 * @param response - 紧凑JSON响应
 * @returns 包含数据和元数据的对象
 * @throws 当响应格式无效时抛出错误
 */
export declare function parseCompactJsonWithMeta(response: CompactJsonResponse): ParseResult;

/**
 * 核心转换函数：将fields和rows转换为对象数组
 * @param fields - 字段名数组
 * @param rows - 数据行数组
 * @returns 转换后的对象数组
 * @throws 当输入参数无效时抛出错误
 */
export declare function compactToObjects(fields: string[], rows: any[][]): Record<string, any>[];

/**
 * 验证紧凑JSON格式是否有效
 * @param response - 待验证的响应数据
 * @returns 如果格式有效返回true，否则返回false
 */
export declare function validateCompactJson(response: any): boolean;

/**
 * 创建字段映射器函数，用于快速转换行数据
 * @param fields - 字段名数组
 * @returns 映射器函数，接受行数组返回对象
 */
export declare function createFieldMapper(fields: string[]): FieldMapper;

/**
 * 股票数据专用解析器
 * @param response - 紧凑JSON响应
 * @returns 解析后的股票数据数组
 */
export declare function parseStockData(response: CompactJsonResponse): Record<string, any>[];

/**
 * 基金数据专用解析器
 * @param response - 紧凑JSON响应
 * @returns 解析后的基金数据数组
 */
export declare function parseFundData(response: CompactJsonResponse): Record<string, any>[];

/**
 * 指数数据专用解析器
 * @param response - 紧凑JSON响应
 * @returns 解析后的指数数据数组
 */
export declare function parseIndexData(response: CompactJsonResponse): Record<string, any>[];

/**
 * 清空缓存（用于测试或内存管理）
 */
export declare function clearCache(): void;

/**
 * 获取缓存统计信息
 * @returns 缓存统计信息
 */
export declare function getCacheStats(): CacheStats;

/**
 * 紧凑JSON工具类（浏览器环境）
 */
export interface CompactJsonUtils {
    parseCompactJson: typeof parseCompactJson;
    parseCompactJsonWithMeta: typeof parseCompactJsonWithMeta;
    compactToObjects: typeof compactToObjects;
    validateCompactJson: typeof validateCompactJson;
    createFieldMapper: typeof createFieldMapper;
    parseStockData: typeof parseStockData;
    parseFundData: typeof parseFundData;
    parseIndexData: typeof parseIndexData;
    clearCache: typeof clearCache;
    getCacheStats: typeof getCacheStats;
}

/**
 * 全局命名空间声明（浏览器环境）
 */
declare global {
    interface Window {
        CompactJsonUtils: CompactJsonUtils;
    }
}

/**
 * Node.js模块导出
 */
export as namespace CompactJsonUtils;