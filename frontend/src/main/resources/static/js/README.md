# AlphaFrog 紧凑JSON工具函数库

## 概述

AlphaFrog紧凑JSON工具函数库提供了一套完整的解决方案，用于解析和处理API响应中的紧凑JSON格式（fields+rows结构）。该库支持股票、基金、指数等多种金融数据类型，具有高性能、易用性和向后兼容性。

## 特性

- 🚀 **高性能**：使用字段映射器缓存，大幅提升解析速度
- 📦 **多数据类型支持**：专门的股票、基金、指数数据解析器
- 🔧 **智能类型转换**：自动识别和处理日期、数值等数据类型
- 🛡️ **完善的错误处理**：详细的错误信息和验证机制
- 📱 **向后兼容**：支持标准JSON格式和紧凑JSON格式
- 🎯 **TypeScript支持**：完整的类型定义文件
- 📊 **性能监控**：内置缓存统计和内存管理

## 文件结构

```
frontend/src/main/resources/static/js/
├── compactJsonUtils.js          # 核心工具函数库
├── compactJsonUtils.d.ts        # TypeScript类型定义
├── compactJsonUtils.example.js  # 使用示例
├── compactJsonUtils.test.js     # 单元测试
└── README.md                    # 本文档
```

## 快速开始

### 1. 引入工具库

#### 浏览器环境
```html
<script src="compactJsonUtils.js"></script>
<script>
    // 工具函数现在可以通过 window.CompactJsonUtils 访问
    const data = CompactJsonUtils.parseCompactJson(response);
</script>
```

#### Node.js环境
```javascript
const CompactJsonUtils = require('./compactJsonUtils.js');
// 或者使用ES6模块
// import { parseCompactJson, parseStockData } from './compactJsonUtils.js';

const data = CompactJsonUtils.parseCompactJson(response);
```

### 2. 基本使用示例

```javascript
// 紧凑JSON响应示例
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

// 解析紧凑JSON
try {
    const data = parseCompactJson(compactResponse);
    console.log(data);
    // 输出:
    // [
    //   { ts_code: "000001.SZ", trade_date: Date对象, close: 15.68, open: 15.45, high: 15.89, low: 15.32, vol: 1234567 },
    //   { ts_code: "000002.SZ", trade_date: Date对象, close: 28.45, open: 28.12, high: 28.67, low: 28.05, vol: 2345678 }
    // ]
} catch (error) {
    console.error('解析失败:', error.message);
}
```

## API参考

### 核心函数

#### `parseCompactJson(response)`
将紧凑JSON解析为对象数组。

**参数：**
- `response` (Object): 紧凑JSON响应对象

**返回：**
- `Array<Object>`: 解析后的对象数组

**示例：**
```javascript
const data = parseCompactJson(compactResponse);
```

#### `parseCompactJsonWithMeta(response)`
将紧凑JSON解析为包含数据和元数据的对象。

**参数：**
- `response` (Object): 紧凑JSON响应对象

**返回：**
- `Object`: `{ data: Array<Object>, meta: Object|null }`

**示例：**
```javascript
const result = parseCompactJsonWithMeta(compactResponse);
console.log(result.data); // 解析后的数据
console.log(result.meta); // 元数据信息
```

#### `compactToObjects(fields, rows)`
核心转换函数，将fields和rows转换为对象数组。

**参数：**
- `fields` (Array<string>): 字段名数组
- `rows` (Array<Array<any>>): 数据行数组

**返回：**
- `Array<Object>`: 转换后的对象数组

**示例：**
```javascript
const fields = ["ts_code", "close", "vol"];
const rows = [["000001.SZ", 15.68, 1234567]];
const data = compactToObjects(fields, rows);
```

#### `validateCompactJson(response)`
验证紧凑JSON格式是否有效。

**参数：**
- `response` (any): 待验证的响应数据

**返回：**
- `boolean`: 如果格式有效返回true，否则返回false

**示例：**
```javascript
if (validateCompactJson(response)) {
    const data = parseCompactJson(response);
} else {
    console.error('无效的紧凑JSON格式');
}
```

#### `createFieldMapper(fields)`
创建字段映射器函数，用于快速转换行数据。

**参数：**
- `fields` (Array<string>): 字段名数组

**返回：**
- `Function`: 映射器函数，接受行数组返回对象

**示例：**
```javascript
const mapper = createFieldMapper(["ts_code", "close", "vol"]);
const obj = mapper(["000001.SZ", 15.68, 1234567]);
// 输出: { ts_code: "000001.SZ", close: 15.68, vol: 1234567 }
```

### 专用数据解析器

#### `parseStockData(response)`
股票数据专用解析器，自动处理股票相关的数据类型转换。

**参数：**
- `response` (Object): 紧凑JSON响应对象

**返回：**
- `Array<Object>`: 解析后的股票数据数组

**特性：**
- 股票代码保持字符串格式
- 价格字段转换为数值类型
- 涨跌幅转换为数值类型
- 成交量和金额转换为数值类型
- 交易日期转换为Date对象

**示例：**
```javascript
const stockData = parseStockData(stockResponse);
```

#### `parseFundData(response)`
基金数据专用解析器，自动处理基金相关的数据类型转换。

**参数：**
- `response` (Object): 紧凑JSON响应对象

**返回：**
- `Array<Object>`: 解析后的基金数据数组

**特性：**
- 基金代码保持字符串格式
- 净值字段转换为数值类型
- 资产字段转换为数值类型
- 日期字段转换为Date对象

**示例：**
```javascript
const fundData = parseFundData(fundResponse);
```

#### `parseIndexData(response)`
指数数据专用解析器，自动处理指数相关的数据类型转换。

**参数：**
- `response` (Object): 紧凑JSON响应对象

**返回：**
- `Array<Object>`: 解析后的指数数据数组

**特性：**
- 指数代码保持字符串格式
- 价格字段转换为数值类型
- 涨跌幅转换为数值类型
- 成交量和金额转换为数值类型
- 交易日期转换为Date对象

**示例：**
```javascript
const indexData = parseIndexData(indexResponse);
```

### 缓存管理

#### `clearCache()`
清空字段映射器和类型转换器缓存。

**示例：**
```javascript
clearCache(); // 在内存紧张或测试时使用
```

#### `getCacheStats()`
获取缓存统计信息。

**返回：**
- `Object`: `{ fieldMapperCacheSize: number, typeConverterCacheSize: number }`

**示例：**
```javascript
const stats = getCacheStats();
console.log(`字段映射器缓存大小: ${stats.fieldMapperCacheSize}`);
```

## 实际应用示例

### AJAX请求处理

```javascript
// 使用jQuery
$.ajax({
    url: '/domestic/stock/daily/ts_code',
    data: {
        ts_code: '000001.SZ',
        start_date: '20240101',
        end_date: '20240131',
        format: 'compact'
    },
    success: function(response) {
        if (!validateCompactJson(response)) {
            console.error('无效的紧凑JSON格式');
            return;
        }
        
        const stockData = parseStockData(response);
        updateTable(stockData);
        
        if (response.meta) {
            showMetaInfo(response.meta);
        }
    }
});

// 使用Fetch API
async function fetchData() {
    try {
        const response = await fetch('/domestic/fund/nav/ts_code?ts_code=000001.OF&format=compact');
        const compactData = await response.json();
        
        if (!validateCompactJson(compactData)) {
            throw new Error('无效的紧凑JSON格式');
        }
        
        const fundData = parseFundData(compactData);
        return fundData;
    } catch (error) {
        console.error('数据获取失败:', error.message);
        return [];
    }
}
```

### 批量数据处理

```javascript
async function processMultipleRequests() {
    const requests = [
        fetch('/domestic/stock/daily/ts_code?ts_code=000001.SZ&format=compact'),
        fetch('/domestic/fund/nav/ts_code?ts_code=000001.OF&format=compact'),
        fetch('/domestic/index/quote/daily?ts_code=000001.SH&format=compact')
    ];
    
    const responses = await Promise.all(requests);
    const results = { stocks: [], funds: [], indices: [] };
    
    for (const response of responses) {
        const data = await response.json();
        
        if (!validateCompactJson(data)) continue;
        
        // 根据字段名判断数据类型
        if (data.fields.includes('unit_nav')) {
            results.funds.push(...parseFundData(data));
        } else if (data.fields.includes('pct_chg')) {
            if (data.rows[0] && data.rows[0][0].endsWith('.OF')) {
                // 跳过，已经是基金数据
            } else if (data.rows[0] && (data.rows[0][0].endsWith('.SH') || data.rows[0][0].endsWith('.SZ'))) {
                results.indices.push(...parseIndexData(data));
            } else {
                results.stocks.push(...parseStockData(data));
            }
        }
    }
    
    return results;
}
```

### 内存管理

```javascript
function processLargeDataset() {
    // 处理大量数据时的内存管理
    const largeResponse = {
        format: "compact",
        fields: ["ts_code", "trade_date", "close", "vol"],
        rows: [] // 假设这里有10000+条数据
    };
    
    console.log('处理前缓存统计:', getCacheStats());
    
    const startTime = performance.now();
    const data = parseStockData(largeResponse);
    const endTime = performance.now();
    
    console.log(`处理 ${data.length} 条记录耗时:`, endTime - startTime, 'ms');
    console.log('处理后缓存统计:', getCacheStats());
    
    // 如果缓存过大，清空缓存
    if (getCacheStats().fieldMapperCacheSize > 100) {
        clearCache();
        console.log('清空缓存后的统计:', getCacheStats());
    }
}
```

## 性能优化建议

1. **使用专用解析器**：对于股票、基金、指数数据，使用对应的专用解析器(`parseStockData`、`parseFundData`、`parseIndexData`)，它们会自动进行最优的数据类型转换。

2. **重用字段映射器**：如果需要多次转换相同结构的字段，可以手动创建字段映射器并重用：
   ```javascript
   const mapper = createFieldMapper(["ts_code", "close", "vol"]);
   const data1 = rows1.map(row => mapper(row));
   const data2 = rows2.map(row => mapper(row));
   ```

3. **批量处理**：尽量批量处理数据，减少函数调用开销。

4. **内存管理**：对于大量数据处理，定期检查和清空缓存：
   ```javascript
   if (getCacheStats().fieldMapperCacheSize > 1000) {
       clearCache();
   }
   ```

5. **验证前置**：在解析前先验证数据格式，避免不必要的解析开销：
   ```javascript
   if (validateCompactJson(response)) {
       const data = parseCompactJson(response);
   }
   ```

## 错误处理

库提供了完善的错误处理机制，建议在使用时进行适当的错误捕获：

```javascript
try {
    if (!validateCompactJson(response)) {
        throw new Error('无效的紧凑JSON格式');
    }
    
    const data = parseStockData(response);
    
    // 进一步处理数据...
} catch (error) {
    console.error('数据处理失败:', error.message);
    
    // 可以回退到标准格式或其他处理方式
    // const data = parseStandardJson(standardResponse);
}
```

## 浏览器兼容性

- **现代浏览器**：Chrome 45+, Firefox 40+, Safari 10+, Edge 12+
- **IE浏览器**：不支持（建议使用polyfill或转译器）
- **移动端**：iOS Safari 10+, Android Chrome 45+

## TypeScript支持

提供了完整的TypeScript类型定义文件(`compactJsonUtils.d.ts`)，支持类型检查和智能提示：

```typescript
import { CompactJsonResponse, parseCompactJson, parseStockData } from './compactJsonUtils';

const response: CompactJsonResponse = {
    format: "compact",
    fields: ["ts_code", "close"],
    rows: [["000001.SZ", 15.68]]
};

const data: Record<string, any>[] = parseCompactJson(response);
const stockData: Record<string, any>[] = parseStockData(response);
```

## 测试

提供了完整的单元测试(`compactJsonUtils.test.js`)，可以使用Jest或其他测试框架运行：

```bash
# 安装Jest
npm install --save-dev jest

# 运行测试
jest compactJsonUtils.test.js
```

## 更新日志

### v1.0.0 (2024-01-03)
- ✨ 初始版本发布
- ✨ 支持基本的紧凑JSON解析功能
- ✨ 提供股票、基金、指数专用解析器
- ✨ 完整的TypeScript类型定义
- ✨ 性能优化和缓存机制
- ✨ 完善的错误处理和验证
- ✨ 详细的文档和示例

## 贡献指南

欢迎提交Issue和Pull Request来改进这个工具库。在提交前请：

1. 运行所有单元测试确保通过
2. 更新相关文档
3. 遵循现有的代码风格
4. 添加必要的测试用例

## 许可证

MIT License - 详见项目根目录的LICENSE文件
