/**
 * AlphaFrog 紧凑JSON工具函数使用示例
 * 
 * 本文件演示如何使用compactJsonUtils.js中的各种功能
 */

// 示例1：基本的紧凑JSON解析
function basicExample() {
    // 模拟从API获取的紧凑JSON响应
    const compactResponse = {
        format: "compact",
        fields: ["ts_code", "trade_date", "close", "open", "high", "low", "vol"],
        rows: [
            ["000001.SZ", "20240101", 15.68, 15.45, 15.89, 15.32, 1234567],
            ["000002.SZ", "20240101", 28.45, 28.12, 28.67, 28.05, 2345678]
        ],
        meta: {
            tsCode: "000001.SZ,000002.SZ",
            startDate: "20240101",
            endDate: "20240101",
            actualTradingDays: 1
        }
    };

    try {
        // 解析紧凑JSON
        const data = parseCompactJson(compactResponse);
        console.log('解析结果:', data);
        
        // 输出:
        // [
        //   { ts_code: "000001.SZ", trade_date: Date对象, close: 15.68, open: 15.45, high: 15.89, low: 15.32, vol: 1234567 },
        //   { ts_code: "000002.SZ", trade_date: Date对象, close: 28.45, open: 28.12, high: 28.67, low: 28.05, vol: 2345678 }
        // ]
    } catch (error) {
        console.error('解析失败:', error.message);
    }
}

// 示例2：使用parseCompactJsonWithMeta获取元数据
function metaDataExample() {
    const compactResponse = {
        format: "compact",
        fields: ["ts_code", "nav_date", "unit_nav", "accum_nav"],
        rows: [
            ["000001.OF", 1672531200000, 1.2345, 1.3456],
            ["000001.OF", 1672444800000, 1.2334, 1.3445]
        ],
        meta: {
            tsCode: "000001.OF",
            startDate: 1672444800000,
            endDate: 1672531200000,
            actualTradingDays: 2,
            complete: true
        }
    };

    try {
        const result = parseCompactJsonWithMeta(compactResponse);
        console.log('数据:', result.data);
        console.log('元数据:', result.meta);
        
        // 可以使用元数据进行后续处理
        if (result.meta && result.meta.complete) {
            console.log('数据完整性检查通过');
        }
    } catch (error) {
        console.error('解析失败:', error.message);
    }
}

// 示例3：使用专用解析器处理股票数据
function stockDataExample() {
    const stockCompactResponse = {
        format: "compact",
        fields: ["ts_code", "trade_date", "close", "open", "high", "low", "pre_close", "change", "pct_chg", "vol", "amount"],
        rows: [
            ["000001.SZ", "20240101", 15.68, 15.45, 15.89, 15.32, 15.23, 0.45, 2.96, 1234567, 18945678.90],
            ["000002.SZ", "20240101", 28.45, 28.12, 28.67, 28.05, 27.98, 0.47, 1.68, 2345678, 34567890.12]
        ]
    };

    try {
        // 使用专用的股票数据解析器
        const stockData = parseStockData(stockCompactResponse);
        console.log('股票数据解析结果:', stockData);
        
        // 股票数据解析器会自动处理:
        // - 股票代码保持字符串格式
        // - 价格相关字段转换为数值类型
        // - 涨跌幅转换为数值类型
        // - 成交量和金额转换为数值类型
        // - 交易日期转换为Date对象
    } catch (error) {
        console.error('股票数据解析失败:', error.message);
    }
}

// 示例4：使用专用解析器处理基金数据
function fundDataExample() {
    const fundCompactResponse = {
        format: "compact",
        fields: ["ts_code", "nav_date", "unit_nav", "accum_nav", "adj_nav", "net_asset", "total_net_asset"],
        rows: [
            ["000001.OF", 1672531200000, 1.2345, 1.3456, 1.2345, 1234567890, 12345678900],
            ["000001.OF", 1672444800000, 1.2334, 1.3445, 1.2334, 1234567890, 12345678900]
        ]
    };

    try {
        // 使用专用的基金数据解析器
        const fundData = parseFundData(fundCompactResponse);
        console.log('基金数据解析结果:', fundData);
        
        // 基金数据解析器会自动处理:
        // - 基金代码保持字符串格式
        // - 净值相关字段转换为数值类型
        // - 资产相关字段转换为数值类型
        // - 日期字段转换为Date对象
    } catch (error) {
        console.error('基金数据解析失败:', error.message);
    }
}

// 示例5：使用专用解析器处理指数数据
function indexDataExample() {
    const indexCompactResponse = {
        format: "compact",
        fields: ["ts_code", "trade_date", "close", "open", "high", "low", "pre_close", "change", "pct_chg", "vol", "amount"],
        rows: [
            ["000001.SH", "20240101", 3089.26, 3078.45, 3095.89, 3072.32, 3069.26, 20.00, 0.65, 1234567, 18945678.90],
            ["399001.SZ", "20240101", 9826.45, 9812.12, 9834.67, 9805.05, 9801.45, 25.00, 0.25, 2345678, 34567890.12]
        ]
    };

    try {
        // 使用专用的指数数据解析器
        const indexData = parseIndexData(indexCompactResponse);
        console.log('指数数据解析结果:', indexData);
        
        // 指数数据解析器会自动处理:
        // - 指数代码保持字符串格式
        // - 价格相关字段转换为数值类型
        // - 涨跌幅转换为数值类型
        // - 成交量和金额转换为数值类型
        // - 交易日期转换为Date对象
    } catch (error) {
        console.error('指数数据解析失败:', error.message);
    }
}

// 示例6：手动转换fields和rows
function manualConversionExample() {
    const fields = ["ts_code", "trade_date", "close", "vol"];
    const rows = [
        ["000001.SZ", "20240101", 15.68, 1234567],
        ["000002.SZ", "20240101", 28.45, 2345678]
    ];

    try {
        // 使用compactToObjects进行转换
        const data = compactToObjects(fields, rows);
        console.log('手动转换结果:', data);
        
        // 输出与parseCompactJson相同的结果
    } catch (error) {
        console.error('转换失败:', error.message);
    }
}

// 示例7：验证紧凑JSON格式
function validationExample() {
    const validResponse = {
        format: "compact",
        fields: ["ts_code", "trade_date", "close"],
        rows: [["000001.SZ", "20240101", 15.68]]
    };

    const invalidResponse = {
        format: "standard", // 错误的格式
        fields: ["ts_code", "trade_date", "close"],
        rows: [["000001.SZ", "20240101", 15.68]]
    };

    console.log('有效响应验证:', validateCompactJson(validResponse)); // true
    console.log('无效响应验证:', validateCompactJson(invalidResponse)); // false
}

// 示例8：性能优化 - 使用字段映射器缓存
function performanceOptimizationExample() {
    const fields = ["ts_code", "trade_date", "close", "open", "high", "low", "vol", "amount"];
    const rows1 = [["000001.SZ", "20240101", 15.68, 15.45, 15.89, 15.32, 1234567, 18945678.90]];
    const rows2 = [["000002.SZ", "20240101", 28.45, 28.12, 28.67, 28.05, 2345678, 34567890.12]];

    // 第一次调用会创建字段映射器并缓存
    const start1 = performance.now();
    const data1 = compactToObjects(fields, rows1);
    const end1 = performance.now();
    console.log('第一次转换耗时:', end1 - start1, 'ms');

    // 第二次调用会使用缓存的字段映射器，性能更好
    const start2 = performance.now();
    const data2 = compactToObjects(fields, rows2);
    const end2 = performance.now();
    console.log('第二次转换耗时:', end2 - start2, 'ms');

    // 查看缓存统计
    console.log('缓存统计:', getCacheStats());
}

// 示例9：错误处理
function errorHandlingExample() {
    const invalidResponses = [
        null,
        undefined,
        {},
        { format: "standard" },
        { format: "compact", fields: "not array" },
        { format: "compact", fields: [], rows: "not array" },
        { format: "compact", fields: ["ts_code"], rows: [["000001.SZ", "extra"]] } // 列数不匹配
    ];

    invalidResponses.forEach((response, index) => {
        try {
            const data = parseCompactJson(response);
            console.log(`测试 ${index + 1}: 意外成功`, data);
        } catch (error) {
            console.log(`测试 ${index + 1}: 预期错误 -`, error.message);
        }
    });
}

// 示例10：实际AJAX请求使用场景
function ajaxExample() {
    // 使用jQuery的示例
    function fetchStockDataWithjQuery() {
        $.ajax({
            url: '/domestic/stock/daily/ts_code',
            data: {
                ts_code: '000001.SZ',
                start_date: '20240101',
                end_date: '20240131',
                format: 'compact' // 关键：请求紧凑格式
            },
            success: function(response) {
                try {
                    // 验证响应格式
                    if (!validateCompactJson(response)) {
                        console.error('无效的紧凑JSON格式');
                        return;
                    }
                    
                    // 解析股票数据
                    const stockData = parseStockData(response);
                    
                    // 使用解析后的数据更新UI
                    updateStockTable(stockData);
                    
                    // 显示元数据信息
                    if (response.meta) {
                        showMetaInfo(response.meta);
                    }
                } catch (error) {
                    console.error('数据处理失败:', error.message);
                }
            },
            error: function(xhr, status, error) {
                console.error('AJAX请求失败:', error);
            }
        });
    }

    // 使用Fetch API的示例
    async function fetchFundDataWithFetch() {
        try {
            const response = await fetch('/domestic/fund/nav/ts_code?ts_code=000001.OF&format=compact');
            const compactData = await response.json();
            
            // 验证响应格式
            if (!validateCompactJson(compactData)) {
                console.error('无效的紧凑JSON格式');
                return;
            }
            
            // 解析基金数据
            const fundData = parseFundData(compactData);
            
            // 使用解析后的数据
            console.log('基金净值数据:', fundData);
            
        } catch (error) {
            console.error('数据获取或处理失败:', error.message);
        }
    }

    // 辅助函数
    function updateStockTable(data) {
        // 更新表格的示例实现
        const tableBody = document.querySelector('#stock-table tbody');
        tableBody.innerHTML = '';
        
        data.forEach(row => {
            const tr = document.createElement('tr');
            tr.innerHTML = `
                <td>${row.ts_code}</td>
                <td>${row.trade_date.toLocaleDateString()}</td>
                <td>${row.close.toFixed(2)}</td>
                <td>${row.pct_chg.toFixed(2)}%</td>
                <td>${row.vol.toLocaleString()}</td>
            `;
            tableBody.appendChild(tr);
        });
    }

    function showMetaInfo(meta) {
        // 显示元数据信息的示例实现
        const metaDiv = document.querySelector('#meta-info');
        if (metaDiv) {
            metaDiv.innerHTML = `
                <p>数据完整性: ${meta.complete ? '完整' : '不完整'}</p>
                <p>交易日数量: ${meta.actualTradingDays}</p>
                <p>时间范围: ${new Date(meta.startDate).toLocaleDateString()} - ${new Date(meta.endDate).toLocaleDateString()}</p>
            `;
        }
    }
}

// 示例11：批量处理多个紧凑JSON响应
function batchProcessingExample() {
    const responses = [
        {
            format: "compact",
            fields: ["ts_code", "trade_date", "close"],
            rows: [["000001.SZ", "20240101", 15.68]]
        },
        {
            format: "compact",
            fields: ["ts_code", "nav_date", "unit_nav"],
            rows: [["000001.OF", 1672531200000, 1.2345]]
        },
        {
            format: "compact",
            fields: ["ts_code", "trade_date", "close"],
            rows: [["000001.SH", "20240101", 3089.26]]
        }
    ];

    const results = {
        stocks: [],
        funds: [],
        indices: []
    };

    responses.forEach(response => {
        try {
            if (validateCompactJson(response)) {
                // 根据字段名判断数据类型
                if (response.fields.includes('unit_nav') || response.fields.includes('nav_date')) {
                    // 基金数据
                    results.funds.push(...parseFundData(response));
                } else if (response.fields.includes('pct_chg') && response.fields.includes('pre_close')) {
                    // 股票或指数数据，需要进一步判断
                    const sampleData = parseCompactJson(response);
                    if (sampleData[0] && sampleData[0].ts_code && sampleData[0].ts_code.endsWith('.SZ') || sampleData[0].ts_code.endsWith('.SS')) {
                        results.stocks.push(...parseStockData(response));
                    } else {
                        results.indices.push(...parseIndexData(response));
                    }
                }
            }
        } catch (error) {
            console.error('批量处理失败:', error.message);
        }
    });

    console.log('批量处理结果:', results);
}

// 示例12：内存管理和性能监控
function memoryManagementExample() {
    // 处理大量数据时的内存管理
    function processLargeDataset() {
        // 模拟大量数据
        const largeResponse = {
            format: "compact",
            fields: ["ts_code", "trade_date", "close", "open", "high", "low", "vol", "amount"],
            rows: []
        };

        // 生成10000条测试数据
        for (let i = 0; i < 10000; i++) {
            largeResponse.rows.push([
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

        console.log('处理前缓存统计:', getCacheStats());

        const startTime = performance.now();
        const data = parseStockData(largeResponse);
        const endTime = performance.now();

        console.log(`处理 ${data.length} 条记录耗时:`, endTime - startTime, 'ms');
        console.log('处理后缓存统计:', getCacheStats());

        // 如果内存紧张，可以清空缓存
        if (getCacheStats().fieldMapperCacheSize > 100) {
            console.log('缓存过大，清空缓存');
            clearCache();
            console.log('清空后缓存统计:', getCacheStats());
        }
    }

    processLargeDataset();
}

// 运行所有示例（在实际使用中，根据需要选择运行）
console.log('=== AlphaFrog 紧凑JSON工具函数使用示例 ===');

console.log('\n1. 基本解析示例:');
basicExample();

console.log('\n2. 元数据示例:');
metaDataExample();

console.log('\n3. 股票数据解析示例:');
stockDataExample();

console.log('\n4. 基金数据解析示例:');
fundDataExample();

console.log('\n5. 指数数据解析示例:');
indexDataExample();

console.log('\n6. 手动转换示例:');
manualConversionExample();

console.log('\n7. 验证示例:');
validationExample();

console.log('\n8. 性能优化示例:');
performanceOptimizationExample();

console.log('\n9. 错误处理示例:');
errorHandlingExample();

console.log('\n10. 内存管理示例:');
memoryManagementExample();

console.log('\n=== 示例运行完成 ===');

// 导出示例函数供其他模块使用
if (typeof module !== 'undefined' && module.exports) {
    module.exports = {
        basicExample,
        metaDataExample,
        stockDataExample,
        fundDataExample,
        indexDataExample,
        manualConversionExample,
        validationExample,
        performanceOptimizationExample,
        errorHandlingExample,
        ajaxExample,
        batchProcessingExample,
        memoryManagementExample
    };
}