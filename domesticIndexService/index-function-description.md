规定：以下所有时间戳的时区默认为北京时间（Asia/Shanghai）。


## 指数业务函数

### 根据指数代码和日期范围查询日线行情

向 `{{baseUrl}}/domestic/index/quote/daily`发送GET请求，需要params：

- ts_code：需要查询的指数代码
- start_date_timestamp：查询起始日期（时分秒统一为00:00:00）的毫秒时间戳
- end_date_timestamp：查询结束日期（时分秒统一为00:00:00）的毫秒时间戳

示例的合法请求：
```
{{baseUrl}}/domestic/index/quote/daily?ts_code=000300.SH&start_date_timestamp=1704038400000&end_date_timestamp=1735574400000
```
以下所述的所有业务接口，若非特别说明，否则合法请求格式均如上所示，即均为GET请求+request params。


返回格式：
```json
{
	"items": [
		{
			(第一个日期的日线数据)
		},
		{
			(第二个日期的日线数据)
		},
		...
	]
}
```

其中，返回日线单个数据的示例是（注意所有属性的返回格式）：
```json
{
  "tsCode": "000300.SH",
  "tradeDate": "1704124800000",  // 注意该属性返回的是字符串格式
  "close": 3386.3522,
  "open": 3426.2684,  // 该属性可能为null
  "high": 3426.2684,  // 该属性可能为null
  "low": 3386.3522,   // 该属性可能为null
  "preClose": 3431.1099,
  "change": -44.7577, 
  "pctChg": -1.3045,
  "vol": 1.16180726E8, // 该属性可能为null
  "amount": 1.84096097449E8  // 该属性可能为null
}
```


### 根据指数代码查询指数信息

向 `{{baseUrl}}/domestic/index/info/ts_code`发送GET请求，需要参数：

- ts_code： 需要查询的指数代码


返回格式示例（注意所有属性的返回格式）：
```json
{
    "item": {
        "tsCode": "000300.SH",
        "name": "沪深300",
        "fullname": "沪深300指数",
        "market": "SSE",
        "publisher": "中证指数有限公司",
        "indexType": "中证规模指数",		// 该属性可能为null
        "category": "规模指数",				 // 该属性可能为null
        "baseDate": "1104422400000",  // 这是时间戳。注意如果需要用到该属性，则需要将其转换为long
        "basePoint": 1000.0,
        "listDate": 1.1128896E12,
        "weightRule": "其他",					// 该属性可能为null
        "desc": "沪深300指数由沪深市场中规模大、流动性好的最具代表性的300只证券组成，于2005年4月8日正式发布，以反映沪深市场上市公司证券的整体表现。"
    }
}
```

### 根据关键词搜索指数

向`{{baseUrl}}/domestic/index/search`发送GET请求，需要参数：

- query：需要查询的关键词


返回格式示例（注意所有属性的返回格式）：
```json
{
    "items": [
        {
            "tsCode": "930955.CSI",
            "name": "红利低波100",
            "fullname": "中证红利低波动100指数",
            "market": "CSI"
        },
        {
            第二个搜索结果（如果有）……
        },
        ...
    ]
}
```


### 根据指数代码和日期范围查询指数成分股


向`{{baseUrl}}/domestic/index/weight/ts_code`发送GET请求，需要参数：

- ts_code： 需要查询的指数代码
- start_date_timestamp：查询起始日期（时分秒统一为00:00:00）的毫秒时间戳
- end_date_timestamp：查询结束日期（时分秒统一为00:00:00）的毫秒时间戳

返回格式示例（注意所有属性的返回格式）：

```json
{
    "items": [
        {
            "indexCode": "000171.CSI", // 查询的指数代码
            "conCode": "300059.SZ",  // 成分股代码
            "tradeDate": "1706630400000",  // 这是时间戳。需要处理这个字段的时候，请先将其转换为long
            "weight": 9.529  // 表示9.529%
        },
        ...
    ]
}
```


### 根据成分股代码和日期范围查询指数


向`{{baseUrl}}/domestic/index/weight/con_code`发送GET请求，需要参数：

- con_code： 需要查询的成分股代码
- start_date_timestamp：查询起始日期（时分秒统一为00:00:00）的毫秒时间戳
- end_date_timestamp：查询结束日期（时分秒统一为00:00:00）的毫秒时间戳



返回格式示例（注意所有属性的返回格式）：

```json
{
    "items": [
        {
            "indexCode": "000171.CSI", // 查询到的含有指定成分股的指数代码
            "conCode": "300059.SZ", // 查询的成分股代码
            "tradeDate": "1735574400000",  // 成分登记时间戳。时间戳处理要点和前述相同
            "weight": 9.529  // 表示9.529%
        },
        ...
    ]
}
```

注意到，若给定的时间间隔超过了指数更新成分股权重的时间间隔，那么会存在多个indexCode和conCode相同，tradeDate和weight不同的记录。
