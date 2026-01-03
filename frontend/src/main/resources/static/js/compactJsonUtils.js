/**
 * AlphaFrog 紧凑JSON格式解析工具
 * 用于将fields+rows格式的紧凑JSON解析为标准的对象数组格式
 * 
 * @author AlphaFrog Team
 * @version 1.0.0
 */

/**
 * 紧凑JSON响应的TypeScript类型定义
 * @typedef {Object} CompactJsonResponse
 * @property {string} format - 格式标识，固定为"compact"
 * @property {string[]} fields - 字段名数组
 * @property {Array<Array<any>>} rows - 数据行数组
 * @property {Object} [meta] - 元数据信息（可选）
 */

/**
 * 解析结果类型定义
 * @typedef {Object} ParseResult
 * @property {Array<Object>} data - 解析后的对象数组
 * @property {Object} [meta] - 元数据信息（如果原始响应包含）
 */

/**
 * 字段映射器缓存，用于提高性能
 * @type {Map<string, Function>}
 */
const fieldMapperCache = new Map();

/**
 * 类型转换器缓存
 * @type {Map<string, Function>}
 */
const typeConverterCache = new Map();

/**
 * 将紧凑JSON解析为对象数组
 * @param {CompactJsonResponse} response - 紧凑JSON响应
 * @returns {Array<Object>} 解析后的对象数组
 * @throws {Error} 当响应格式无效时抛出错误
 */
function parseCompactJson(response) {
    if (!response) {
        throw new Error('响应数据不能为空');
    }

    if (response.format !== 'compact') {
        throw new Error('无效的紧凑JSON格式：format字段必须为"compact"');
    }

    if (!Array.isArray(response.fields)) {
        throw new Error('无效的紧凑JSON格式：fields必须是数组');
    }

    if (!Array.isArray(response.rows)) {
        throw new Error('无效的紧凑JSON格式：rows必须是数组');
    }

    if (response.fields.length === 0) {
        return [];
    }

    if (response.rows.length === 0) {
        return [];
    }

    // 创建字段映射器
    const mapper = createFieldMapper(response.fields);
    
    // 转换所有行
    return response.rows.map(row => mapper(row));
}

/**
 * 将紧凑JSON解析为包含数据和元数据的对象
 * @param {CompactJsonResponse} response - 紧凑JSON响应
 * @returns {ParseResult} 包含数据和元数据的对象
 * @throws {Error} 当响应格式无效时抛出错误
 */
function parseCompactJsonWithMeta(response) {
    const data = parseCompactJson(response);
    return {
        data: data,
        meta: response.meta || null
    };
}

/**
 * 核心转换函数：将fields和rows转换为对象数组
 * @param {string[]} fields - 字段名数组
 * @param {Array<Array<any>>} rows - 数据行数组
 * @returns {Array<Object>} 转换后的对象数组
 * @throws {Error} 当输入参数无效时抛出错误
 */
function compactToObjects(fields, rows) {
    if (!Array.isArray(fields)) {
        throw new Error('fields必须是数组');
    }

    if (!Array.isArray(rows)) {
        throw new Error('rows必须是数组');
    }

    if (fields.length === 0) {
        return [];
    }

    if (rows.length === 0) {
        return [];
    }

    // 创建字段映射器
    const mapper = createFieldMapper(fields);
    
    // 转换所有行
    return rows.map(row => mapper(row));
}

/**
 * 验证紧凑JSON格式是否有效
 * @param {any} response - 待验证的响应数据
 * @returns {boolean} 如果格式有效返回true，否则返回false
 */
function validateCompactJson(response) {
    try {
        if (!response || typeof response !== 'object') {
            return false;
        }

        if (response.format !== 'compact') {
            return false;
        }

        if (!Array.isArray(response.fields) || !Array.isArray(response.rows)) {
            return false;
        }

        // 验证字段名是否都是字符串
        if (response.fields.length > 0) {
            if (!response.fields.every(field => typeof field === 'string')) {
                return false;
            }
        }

        // 验证数据行格式
        if (response.rows.length > 0) {
            if (!response.rows.every(row => Array.isArray(row))) {
                return false;
            }

            // 验证每行的列数是否与字段数匹配
            const fieldCount = response.fields.length;
            if (!response.rows.every(row => row.length === fieldCount)) {
                return false;
            }
        }

        return true;
    } catch (error) {
        return false;
    }
}

/**
 * 创建字段映射器函数，用于快速转换行数据
 * @param {string[]} fields - 字段名数组
 * @returns {Function} 映射器函数，接受行数组返回对象
 */
function createFieldMapper(fields) {
    // 使用缓存提高性能
    const cacheKey = fields.join(',');
    if (fieldMapperCache.has(cacheKey)) {
        return fieldMapperCache.get(cacheKey);
    }

    // 创建映射器函数
    const mapper = function(row) {
        const obj = {};
        for (let i = 0; i < fields.length; i++) {
            const fieldName = fields[i];
            const value = row[i];
            
            // 处理null/undefined值
            if (value === null || value === undefined) {
                obj[fieldName] = null;
            } else {
                // 进行类型转换
                obj[fieldName] = convertFieldValue(value, fieldName);
            }
        }
        return obj;
    };

    // 缓存映射器
    fieldMapperCache.set(cacheKey, mapper);
    return mapper;
}

/**
 * 字段值类型转换函数
 * @param {any} value - 原始值
 * @param {string} fieldName - 字段名
 * @returns {any} 转换后的值
 */
function convertFieldValue(value, fieldName) {
    // 根据字段名进行智能类型转换
    if (typeof value === 'string') {
        // 日期字段转换
        if (fieldName.includes('date') || fieldName.includes('Date')) {
            return convertDateField(value);
        }
        
        // 数值字段转换
        if (fieldName.includes('nav') || fieldName.includes('Nav') || 
            fieldName.includes('price') || fieldName.includes('Price') ||
            fieldName.includes('amount') || fieldName.includes('Amount') ||
            fieldName.includes('ratio') || fieldName.includes('Ratio') ||
            fieldName.includes('chg') || fieldName.includes('Chg')) {
            return convertNumericField(value);
        }
    }
    
    // 数值类型直接返回
    if (typeof value === 'number') {
        return value;
    }
    
    // 布尔类型
    if (typeof value === 'boolean') {
        return value;
    }
    
    // 其他类型保持原样
    return value;
}

/**
 * 日期字段转换
 * @param {string} value - 日期字符串或时间戳
 * @returns {Date|null} Date对象或null
 */
function convertDateField(value) {
    if (!value) return null;
    
    // 如果是数字（时间戳）
    if (/^\d+$/.test(value)) {
        const timestamp = parseInt(value);
        // 判断是秒还是毫秒时间戳
        return new Date(timestamp > 9999999999 ? timestamp : timestamp * 1000);
    }
    
    // 如果是标准日期格式
    const date = new Date(value);
    return isNaN(date.getTime()) ? null : date;
}

/**
 * 数值字段转换
 * @param {string} value - 数值字符串
 * @returns {number|null} 数值或null
 */
function convertNumericField(value) {
    if (!value || value === '' || value === 'null') return null;
    
    const num = parseFloat(value);
    return isNaN(num) ? null : num;
}

/**
 * 股票数据专用解析器
 * @param {CompactJsonResponse} response - 紧凑JSON响应
 * @returns {Array<Object>} 解析后的股票数据数组
 */
function parseStockData(response) {
    const data = parseCompactJson(response);
    
    // 对股票数据进行特殊处理
    return data.map(item => {
        // 确保数值字段正确转换
        const processed = { ...item };
        
        // 股票代码保持字符串
        if (processed.ts_code) processed.ts_code = String(processed.ts_code);
        
        // 价格相关字段转换为数值
        const priceFields = ['open', 'high', 'low', 'close', 'pre_close', 'change'];
        priceFields.forEach(field => {
            if (processed[field] !== null && processed[field] !== undefined) {
                processed[field] = parseFloat(processed[field]) || 0;
            }
        });
        
        // 涨跌幅转换为数值（百分比）
        if (processed.pct_chg !== null && processed.pct_chg !== undefined) {
            processed.pct_chg = parseFloat(processed.pct_chg) || 0;
        }
        
        // 成交量和金额转换为数值
        if (processed.vol !== null && processed.vol !== undefined) {
            processed.vol = parseFloat(processed.vol) || 0;
        }
        if (processed.amount !== null && processed.amount !== undefined) {
            processed.amount = parseFloat(processed.amount) || 0;
        }
        
        // 交易日期转换为Date对象
        if (processed.trade_date) {
            processed.trade_date = convertDateField(processed.trade_date);
        }
        
        return processed;
    });
}

/**
 * 基金数据专用解析器
 * @param {CompactJsonResponse} response - 紧凑JSON响应
 * @returns {Array<Object>} 解析后的基金数据数组
 */
function parseFundData(response) {
    const data = parseCompactJson(response);
    
    // 对基金数据进行特殊处理
    return data.map(item => {
        const processed = { ...item };
        
        // 基金代码保持字符串
        if (processed.ts_code) processed.ts_code = String(processed.ts_code);
        
        // 净值相关字段转换为数值
        const navFields = ['unit_nav', 'accum_nav', 'adj_nav'];
        navFields.forEach(field => {
            if (processed[field] !== null && processed[field] !== undefined) {
                processed[field] = parseFloat(processed[field]) || null;
            }
        });
        
        // 资产相关字段转换为数值
        const assetFields = ['net_asset', 'total_net_asset'];
        assetFields.forEach(field => {
            if (processed[field] !== null && processed[field] !== undefined) {
                processed[field] = parseFloat(processed[field]) || null;
            }
        });
        
        // 日期字段转换为Date对象
        const dateFields = ['nav_date', 'ann_date'];
        dateFields.forEach(field => {
            if (processed[field]) {
                processed[field] = convertDateField(processed[field]);
            }
        });
        
        return processed;
    });
}

/**
 * 指数数据专用解析器
 * @param {CompactJsonResponse} response - 紧凑JSON响应
 * @returns {Array<Object>} 解析后的指数数据数组
 */
function parseIndexData(response) {
    const data = parseCompactJson(response);
    
    // 对指数数据进行特殊处理，类似于股票数据
    return data.map(item => {
        const processed = { ...item };
        
        // 指数代码保持字符串
        if (processed.ts_code) processed.ts_code = String(processed.ts_code);
        
        // 价格相关字段转换为数值
        const priceFields = ['open', 'high', 'low', 'close', 'pre_close', 'change'];
        priceFields.forEach(field => {
            if (processed[field] !== null && processed[field] !== undefined) {
                processed[field] = parseFloat(processed[field]) || 0;
            }
        });
        
        // 涨跌幅转换为数值（百分比）
        if (processed.pct_chg !== null && processed.pct_chg !== undefined) {
            processed.pct_chg = parseFloat(processed.pct_chg) || 0;
        }
        
        // 成交量和金额转换为数值
        if (processed.vol !== null && processed.vol !== undefined) {
            processed.vol = parseFloat(processed.vol) || 0;
        }
        if (processed.amount !== null && processed.amount !== undefined) {
            processed.amount = parseFloat(processed.amount) || 0;
        }
        
        // 交易日期转换为Date对象
        if (processed.trade_date) {
            processed.trade_date = convertDateField(processed.trade_date);
        }
        
        return processed;
    });
}

/**
 * 清空缓存（用于测试或内存管理）
 */
function clearCache() {
    fieldMapperCache.clear();
    typeConverterCache.clear();
}

/**
 * 获取缓存统计信息
 * @returns {Object} 缓存统计信息
 */
function getCacheStats() {
    return {
        fieldMapperCacheSize: fieldMapperCache.size,
        typeConverterCacheSize: typeConverterCache.size
    };
}

// 向后兼容的导出方式
if (typeof module !== 'undefined' && module.exports) {
    // Node.js环境
    module.exports = {
        parseCompactJson,
        parseCompactJsonWithMeta,
        compactToObjects,
        validateCompactJson,
        createFieldMapper,
        parseStockData,
        parseFundData,
        parseIndexData,
        clearCache,
        getCacheStats
    };
} else if (typeof window !== 'undefined') {
    // 浏览器环境
    window.CompactJsonUtils = {
        parseCompactJson,
        parseCompactJsonWithMeta,
        compactToObjects,
        validateCompactJson,
        createFieldMapper,
        parseStockData,
        parseFundData,
        parseIndexData,
        clearCache,
        getCacheStats
    };
}