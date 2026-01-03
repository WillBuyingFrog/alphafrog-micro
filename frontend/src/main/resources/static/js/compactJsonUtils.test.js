/**
 * AlphaFrog 紧凑JSON工具函数单元测试
 * 
 * 使用Jest或类似的测试框架运行这些测试
 * 
 * @author AlphaFrog Team
 * @version 1.0.0
 */

// 如果运行环境不支持这些函数，需要先引入compactJsonUtils.js
// const { parseCompactJson, parseCompactJsonWithMeta, compactToObjects, validateCompactJson, createFieldMapper, parseStockData, parseFundData, parseIndexData, clearCache, getCacheStats } = require('./compactJsonUtils.js');

describe('CompactJsonUtils 单元测试', () => {
    
    // 在每个测试前清空缓存
    beforeEach(() => {
        if (typeof clearCache === 'function') {
            clearCache();
        }
    });

    describe('parseCompactJson', () => {
        test('应该正确解析有效的紧凑JSON', () => {
            const response = {
                format: "compact",
                fields: ["ts_code", "trade_date", "close"],
                rows: [["000001.SZ", "20240101", 15.68]]
            };

            const result = parseCompactJson(response);
            
            expect(result).toHaveLength(1);
            expect(result[0]).toEqual({
                ts_code: "000001.SZ",
                trade_date: expect.any(Date),
                close: 15.68
            });
        });

        test('应该正确处理空数据', () => {
            const response1 = {
                format: "compact",
                fields: [],
                rows: []
            };

            const response2 = {
                format: "compact",
                fields: ["ts_code", "close"],
                rows: []
            };

            expect(parseCompactJson(response1)).toEqual([]);
            expect(parseCompactJson(response2)).toEqual([]);
        });

        test('应该正确处理null和undefined值', () => {
            const response = {
                format: "compact",
                fields: ["ts_code", "close", "vol"],
                rows: [["000001.SZ", null, undefined]]
            };

            const result = parseCompactJson(response);
            
            expect(result[0]).toEqual({
                ts_code: "000001.SZ",
                close: null,
                vol: null
            });
        });

        test('应该抛出错误当响应为null', () => {
            expect(() => parseCompactJson(null)).toThrow('响应数据不能为空');
            expect(() => parseCompactJson(undefined)).toThrow('响应数据不能为空');
        });

        test('应该抛出错误当格式无效', () => {
            const invalidResponses = [
                { format: "standard", fields: [], rows: [] },
                { format: "compact", fields: "not array", rows: [] },
                { format: "compact", fields: [], rows: "not array" }
            ];

            invalidResponses.forEach(response => {
                expect(() => parseCompactJson(response)).toThrow();
            });
        });
    });

    describe('parseCompactJsonWithMeta', () => {
        test('应该正确解析包含元数据的紧凑JSON', () => {
            const response = {
                format: "compact",
                fields: ["ts_code", "close"],
                rows: [["000001.SZ", 15.68]],
                meta: {
                    tsCode: "000001.SZ",
                    complete: true
                }
            };

            const result = parseCompactJsonWithMeta(response);
            
            expect(result.data).toHaveLength(1);
            expect(result.meta).toEqual({
                tsCode: "000001.SZ",
                complete: true
            });
        });

        test('应该处理没有元数据的情况', () => {
            const response = {
                format: "compact",
                fields: ["ts_code", "close"],
                rows: [["000001.SZ", 15.68]]
            };

            const result = parseCompactJsonWithMeta(response);
            
            expect(result.data).toHaveLength(1);
            expect(result.meta).toBeNull();
        });
    });

    describe('compactToObjects', () => {
        test('应该正确转换fields和rows', () => {
            const fields = ["ts_code", "trade_date", "close"];
            const rows = [["000001.SZ", "20240101", 15.68]];

            const result = compactToObjects(fields, rows);
            
            expect(result).toHaveLength(1);
            expect(result[0]).toEqual({
                ts_code: "000001.SZ",
                trade_date: expect.any(Date),
                close: 15.68
            });
        });

        test('应该处理多行数据', () => {
            const fields = ["ts_code", "close"];
            const rows = [
                ["000001.SZ", 15.68],
                ["000002.SZ", 28.45],
                ["000003.SZ", 42.10]
            ];

            const result = compactToObjects(fields, rows);
            
            expect(result).toHaveLength(3);
            expect(result[0].ts_code).toBe("000001.SZ");
            expect(result[1].ts_code).toBe("000002.SZ");
            expect(result[2].ts_code).toBe("000003.SZ");
        });

        test('应该抛出错误当参数无效', () => {
            expect(() => compactToObjects("not array", [])).toThrow('fields必须是数组');
            expect(() => compactToObjects([], "not array")).toThrow('rows必须是数组');
        });
    });

    describe('validateCompactJson', () => {
        test('应该返回true对于有效的紧凑JSON', () => {
            const validResponse = {
                format: "compact",
                fields: ["ts_code", "close"],
                rows: [["000001.SZ", 15.68]]
            };

            expect(validateCompactJson(validResponse)).toBe(true);
        });

        test('应该返回false对于无效的紧凑JSON', () => {
            const invalidResponses = [
                null,
                undefined,
                {},
                { format: "standard" },
                { format: "compact", fields: "not array", rows: [] },
                { format: "compact", fields: [], rows: "not array" },
                { format: "compact", fields: ["ts_code"], rows: [["000001.SZ", "extra"]] }
            ];

            invalidResponses.forEach(response => {
                expect(validateCompactJson(response)).toBe(false);
            });
        });

        test('应该处理空数据', () => {
            const emptyResponse = {
                format: "compact",
                fields: [],
                rows: []
            };

            expect(validateCompactJson(emptyResponse)).toBe(true);
        });
    });

    describe('createFieldMapper', () => {
        test('应该创建有效的字段映射器', () => {
            const fields = ["ts_code", "close", "vol"];
            const mapper = createFieldMapper(fields);

            expect(typeof mapper).toBe('function');

            const row = ["000001.SZ", 15.68, 1234567];
            const result = mapper(row);

            expect(result).toEqual({
                ts_code: "000001.SZ",
                close: 15.68,
                vol: 1234567
            });
        });

        test('应该缓存字段映射器', () => {
            const fields = ["ts_code", "close"];
            const mapper1 = createFieldMapper(fields);
            const mapper2 = createFieldMapper(fields);

            expect(mapper1).toBe(mapper2); // 应该是同一个函数（缓存）
        });
    });

    describe('parseStockData', () => {
        test('应该正确解析股票数据', () => {
            const response = {
                format: "compact",
                fields: ["ts_code", "trade_date", "close", "open", "high", "low", "pre_close", "change", "pct_chg", "vol", "amount"],
                rows: [["000001.SZ", "20240101", 15.68, 15.45, 15.89, 15.32, 15.23, 0.45, 2.96, 1234567, 18945678.90]]
            };

            const result = parseStockData(response);

            expect(result).toHaveLength(1);
            expect(result[0]).toEqual({
                ts_code: "000001.SZ",
                trade_date: expect.any(Date),
                close: 15.68,
                open: 15.45,
                high: 15.89,
                low: 15.32,
                pre_close: 15.23,
                change: 0.45,
                pct_chg: 2.96,
                vol: 1234567,
                amount: 18945678.90
            });
        });

        test('应该处理股票数据中的null值', () => {
            const response = {
                format: "compact",
                fields: ["ts_code", "close", "pct_chg"],
                rows: [["000001.SZ", null, null]]
            };

            const result = parseStockData(response);

            expect(result[0].ts_code).toBe("000001.SZ");
            expect(result[0].close).toBe(0); // null被转换为0
            expect(result[0].pct_chg).toBe(0); // null被转换为0
        });
    });

    describe('parseFundData', () => {
        test('应该正确解析基金数据', () => {
            const response = {
                format: "compact",
                fields: ["ts_code", "nav_date", "unit_nav", "accum_nav", "adj_nav", "net_asset", "total_net_asset"],
                rows: [["000001.OF", 1672531200000, 1.2345, 1.3456, 1.2345, 1234567890, 12345678900]]
            };

            const result = parseFundData(response);

            expect(result).toHaveLength(1);
            expect(result[0]).toEqual({
                ts_code: "000001.OF",
                nav_date: expect.any(Date),
                unit_nav: 1.2345,
                accum_nav: 1.3456,
                adj_nav: 1.2345,
                net_asset: 1234567890,
                total_net_asset: 12345678900
            });
        });

        test('应该处理基金数据中的null值', () => {
            const response = {
                format: "compact",
                fields: ["ts_code", "unit_nav", "net_asset"],
                rows: [["000001.OF", null, null]]
            };

            const result = parseFundData(response);

            expect(result[0].ts_code).toBe("000001.OF");
            expect(result[0].unit_nav).toBeNull(); // null保持为null
            expect(result[0].net_asset).toBeNull(); // null保持为null
        });
    });

    describe('parseIndexData', () => {
        test('应该正确解析指数数据', () => {
            const response = {
                format: "compact",
                fields: ["ts_code", "trade_date", "close", "open", "high", "low", "pre_close", "change", "pct_chg", "vol", "amount"],
                rows: [["000001.SH", "20240101", 3089.26, 3078.45, 3095.89, 3072.32, 3069.26, 20.00, 0.65, 1234567, 18945678.90]]
            };

            const result = parseIndexData(response);

            expect(result).toHaveLength(1);
            expect(result[0]).toEqual({
                ts_code: "000001.SH",
                trade_date: expect.any(Date),
                close: 3089.26,
                open: 3078.45,
                high: 3095.89,
                low: 3072.32,
                pre_close: 3069.26,
                change: 20.00,
                pct_chg: 0.65,
                vol: 1234567,
                amount: 18945678.90
            });
        });
    });

    describe('日期转换', () => {
        test('应该正确转换时间戳字符串', () => {
            const response = {
                format: "compact",
                fields: ["trade_date"],
                rows: [["1672531200000"]] // 毫秒时间戳
            };

            const result = parseCompactJson(response);
            expect(result[0].trade_date).toBeInstanceOf(Date);
            expect(result[0].trade_date.getTime()).toBe(1672531200000);
        });

        test('应该正确转换秒级时间戳字符串', () => {
            const response = {
                format: "compact",
                fields: ["trade_date"],
                rows: [["1672531200"]] // 秒时间戳
            };

            const result = parseCompactJson(response);
            expect(result[0].trade_date).toBeInstanceOf(Date);
            expect(result[0].trade_date.getTime()).toBe(1672531200000);
        });

        test('应该正确处理无效日期', () => {
            const response = {
                format: "compact",
                fields: ["trade_date"],
                rows: [["invalid-date"]]
            };

            const result = parseCompactJson(response);
            expect(result[0].trade_date).toBeNull();
        });
    });

    describe('数值转换', () => {
        test('应该正确转换数值字符串', () => {
            const response = {
                format: "compact",
                fields: ["close", "pct_chg"],
                rows: [["15.68", "2.96"]]
            };

            const result = parseCompactJson(response);
            expect(result[0].close).toBe(15.68);
            expect(result[0].pct_chg).toBe(2.96);
        });

        test('应该正确处理无效数值', () => {
            const response = {
                format: "compact",
                fields: ["close", "vol"],
                rows: [["invalid", ""]]
            };

            const result = parseCompactJson(response);
            expect(result[0].close).toBeNull();
            expect(result[0].vol).toBeNull();
        });
    });

    describe('缓存管理', () => {
        test('应该正确清空缓存', () => {
            // 先创建一些缓存
            createFieldMapper(["ts_code", "close"]);
            createFieldMapper(["nav_date", "unit_nav"]);

            const statsBefore = getCacheStats();
            expect(statsBefore.fieldMapperCacheSize).toBeGreaterThan(0);

            // 清空缓存
            clearCache();

            const statsAfter = getCacheStats();
            expect(statsAfter.fieldMapperCacheSize).toBe(0);
        });

        test('应该正确返回缓存统计', () => {
            clearCache();
            
            const initialStats = getCacheStats();
            expect(initialStats.fieldMapperCacheSize).toBe(0);

            // 创建缓存
            createFieldMapper(["ts_code", "close"]);

            const stats = getCacheStats();
            expect(stats.fieldMapperCacheSize).toBe(1);
        });
    });

    describe('性能测试', () => {
        test('应该高效处理大量数据', () => {
            // 创建大量测试数据
            const fields = ["ts_code", "trade_date", "close", "open", "high", "low", "vol", "amount"];
            const rows = [];
            
            for (let i = 0; i < 1000; i++) {
                rows.push([
                    `00000${i % 1000}.SZ`,
                    `202401${(i % 31) + 1}`,
                    10 + Math.random() * 90,
                    10 + Math.random() * 90,
                    10 + Math.random() * 90,
                    10 + Math.random() * 90,
                    Math.floor(Math.random() * 1000000),
                    Math.floor(Math.random() * 10000000)
                ]);
            }

            const startTime = performance.now();
            const result = compactToObjects(fields, rows);
            const endTime = performance.now();

            expect(result).toHaveLength(1000);
            expect(endTime - startTime).toBeLessThan(100); // 应该在100ms内完成
        });

        test('应该显示缓存性能优势', () => {
            const fields = ["ts_code", "close", "vol"];
            const rows = [["000001.SZ", 15.68, 1234567]];

            // 第一次调用
            const start1 = performance.now();
            compactToObjects(fields, rows);
            const end1 = performance.now();
            const time1 = end1 - start1;

            // 第二次调用（应该使用缓存）
            const start2 = performance.now();
            compactToObjects(fields, rows);
            const end2 = performance.now();
            const time2 = end2 - start2;

            // 第二次调用应该更快（或至少不会慢很多）
            expect(time2).toBeLessThanOrEqual(time1 * 1.1); // 允许10%的误差
        });
    });

    describe('边界情况', () => {
        test('应该处理包含特殊字符的字段名', () => {
            const response = {
                format: "compact",
                fields: ["ts-code", "close_price", "volume@day"],
                rows: [["000001.SZ", 15.68, 1234567]]
            };

            const result = parseCompactJson(response);
            expect(result[0]).toEqual({
                "ts-code": "000001.SZ",
                "close_price": 15.68,
                "volume@day": 1234567
            });
        });

        test('应该处理包含布尔值的数据', () => {
            const response = {
                format: "compact",
                fields: ["ts_code", "is_active", "is_suspended"],
                rows: [["000001.SZ", true, false]]
            };

            const result = parseCompactJson(response);
            expect(result[0]).toEqual({
                ts_code: "000001.SZ",
                is_active: true,
                is_suspended: false
            });
        });

        test('应该处理包含数组的数据', () => {
            const response = {
                format: "compact",
                fields: ["ts_code", "tags"],
                rows: [["000001.SZ", ["tech", "large-cap"]]]
            };

            const result = parseCompactJson(response);
            expect(result[0]).toEqual({
                ts_code: "000001.SZ",
                tags: ["tech", "large-cap"]
            });
        });
    });
});

// 如果运行环境不支持describe，提供简单的测试运行器
if (typeof describe === 'undefined') {
    console.log('=== AlphaFrog 紧凑JSON工具函数测试 ===');
    
    // 简单的测试函数
    function test(name, fn) {
        try {
            fn();
            console.log(`✓ ${name}`);
        } catch (error) {
            console.error(`✗ ${name}: ${error.message}`);
        }
    }
    
    function expect(actual) {
        return {
            toEqual: (expected) => {
                if (JSON.stringify(actual) !== JSON.stringify(expected)) {
                    throw new Error(`Expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
                }
            },
            toBe: (expected) => {
                if (actual !== expected) {
                    throw new Error(`Expected ${expected}, got ${actual}`);
                }
            },
            toBeInstanceOf: (expected) => {
                if (!(actual instanceof expected)) {
                    throw new Error(`Expected instance of ${expected.name}, got ${actual.constructor.name}`);
                }
            },
            toHaveLength: (expected) => {
                if (actual.length !== expected) {
                    throw new Error(`Expected length ${expected}, got ${actual.length}`);
                }
            },
            toBeGreaterThan: (expected) => {
                if (actual <= expected) {
                    throw new Error(`Expected ${actual} to be greater than ${expected}`);
                }
            },
            toBeLessThan: (expected) => {
                if (actual >= expected) {
                    throw new Error(`Expected ${actual} to be less than ${expected}`);
                }
            },
            toBeLessThanOrEqual: (expected) => {
                if (actual > expected) {
                    throw new Error(`Expected ${actual} to be less than or equal to ${expected}`);
                }
            }
        };
    }
    
    // 运行基本测试
    test('基本紧凑JSON解析', () => {
        const response = {
            format: "compact",
            fields: ["ts_code", "close"],
            rows: [["000001.SZ", 15.68]]
        };
        const result = parseCompactJson(response);
        expect(result).toHaveLength(1);
        expect(result[0].ts_code).toBe("000001.SZ");
        expect(result[0].close).toBe(15.68);
    });
    
    test('紧凑JSON验证', () => {
        const validResponse = {
            format: "compact",
            fields: ["ts_code", "close"],
            rows: [["000001.SZ", 15.68]]
        };
        expect(validateCompactJson(validResponse)).toBe(true);
        
        const invalidResponse = { format: "standard" };
        expect(validateCompactJson(invalidResponse)).toBe(false);
    });
    
    test('股票数据解析', () => {
        const response = {
            format: "compact",
            fields: ["ts_code", "close", "pct_chg"],
            rows: [["000001.SZ", 15.68, 2.96]]
        };
        const result = parseStockData(response);
        expect(result[0].ts_code).toBe("000001.SZ");
        expect(result[0].close).toBe(15.68);
        expect(result[0].pct_chg).toBe(2.96);
    });
    
    console.log('=== 测试运行完成 ===');
}