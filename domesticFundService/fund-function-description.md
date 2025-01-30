规定：以下所有时间戳的时区默认为北京时间（Asia/Shanghai）。


## 基金业务函数

### 根据基金代码查询基金信息

向 `{{baseUrl}}/domestic/fund/info/tsCode` 发送 GET 请求,需要参数:

- ts_code:需要查询的基金代码

示例的合法请求:
{{baseUrl}}/domestic/fund/info/tsCode?ts_code=006567.OF


返回格式示例：

```json
{
    "items": [
        {
            "tsCode": "021894.OF",
            "name": "易方达中证半导体材料设备主题联接C",
            "management": "易方达基金",
            "custodian": "中国银行",
            "fundType": "股票型",
            "foundDate": "1732550400000",
            "issueAmount": 3.2293,
            "mFee": 0.5,
            "cFee": 0.1,
            "pValue": 1.0,
            "minAmount": 1.0E-4,
            "benchmark": "中证半导体材料设备主题指数收益率*95%+活期存款利率(税后)*5%",
            "status": "L",
            "investType": "被动指数型",
            "type": "契约型开放式",
            "purcStartDate": "1733414400000",
            "redmStartDate": "1733414400000",
            "market": "O",
            "issueDate": "1730649600000"
        }
    ]
}
```

请注意，除tsCode和name这两个字段必不为null外，其余字段都有可能为null。注意其中所有与日期相关的字段均为毫秒时间戳格式，且为字符串，若要使用，则需要先转换为long类型变量。


### 根据关键词搜索基金

向 `{{baseUrl}}/domestic/fund/search` 发送 GET 请求,需要参数:

- query:需要查询的关键词

返回格式示例：

```json
{
    "items": [
        {
            "tsCode": "021893.OF",
            "name": "易方达中证半导体材料设备主题联接A",
            "management": "易方达基金",
            "fundType": "股票型",
            "foundDate": "1732550400000",
            "benchmark": "中证半导体材料设备主题指数收益率*95%+活期存款利率(税后)*5%"
        },
        ...
    ]
}
```


### 根据基金代码和日期范围查询基金净值

向` {{baseUrl}}/domestic/fund/nav/ts_code`发送 GET 请求,需要参数:

- ts_code:需要查询的基金代码
- start_date_timestamp:查询起始日期(时分秒统一为00:00:00)的毫秒时间戳
- end_date_timestamp:查询结束日期(时分秒统一为00:00:00)的毫秒时间戳

返回格式示例：

```json
{
    "items": [
        {
            "tsCode": "006567.OF",
            "annDate": "1672761600000",	 // 净值公告日期（毫秒时间戳）
            "navDate": "1672675200000",  // 净值日期（毫秒时间戳）
            "unitNav": 2.4277,   // 单位净值
            "accumNav": 2.4277,  // 累计净值
            "adjNav": 2.4277		 // 复权单位净值
        },
        ...
    ]
}
```


### 根据基金代码和日期范围查询基金持仓

向` {{baseUrl}}/domestic/fund/portfolio/ts_code `发送 GET 请求,需要参数:

- ts_code:需要查询的基金代码
- start_date_timestamp:查询起始日期(时分秒统一为00:00:00)的毫秒时间戳
- end_date_timestamp:查询结束日期(时分秒统一为00:00:00)的毫秒时间戳


返回格式示例：

```json
{
    "items": [
        {
            "tsCode": "017415.OF",
            "annDate": "1698163200000",		// 公告日期
            "endDate": "1696003200000",		// 截止日期（即反应的是截至endDate的基金持仓信息）
            "symbol": "601000.SH",				// 持仓股票
            "mkv": 7.950312747E7,					// 持有股票市值（元）
            "amount": 2.1545563E7,				// 持有股票数量（股）
            "sktMkvRatio": 5.11,					// 该持仓占该基金所有持仓的市值比例
            "sktFloatRatio": 0.36					// 占流通股本比例
        },
        ...
    ]
}
```


### 根据成分股代码和日期范围查询基金持仓


向` {{baseUrl}}/domestic/fund/portfolio/symbol` 发送 GET 请求,需要参数:

- symbol:需要查询的成分股代码
- start_date_timestamp:查询起始日期(时分秒统一为00:00:00)的毫秒时间戳
- end_date_timestamp:查询结束日期(时分秒统一为00:00:00)的毫秒时间戳


返回格式示例：

```json
{
    "items": [
        {
            "tsCode": "017415.OF",
            "annDate": "1698163200000",		// 公告日期
            "endDate": "1696003200000",		// 截止日期（即反应的是截至endDate的基金持仓信息）
            "symbol": "601000.SH",				// 持仓股票
            "mkv": 7.950312747E7,					// 持有股票市值（元）
            "amount": 2.1545563E7,				// 持有股票数量（股）
            "sktMkvRatio": 5.11,					// 该持仓占该基金所有持仓的市值比例
            "sktFloatRatio": 0.36					// 占流通股本比例
        },
        ...
    ]
}
```