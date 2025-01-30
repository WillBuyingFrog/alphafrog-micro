## 股票业务函数

### 根据股票代码查询股票基本信息

向`{{baseUrl}}/domestic/stock/info/ts_code`发送GET请求，需要参数：

- ts_code： 需要查询的股票代码


返回格式示例（注意没有“items”，并注意所有属性的返回格式）：
```json
{
    "stockInfoId": "2853",
    "tsCode": "600006.SH",
    "symbol": "600006",
    "name": "东风股份",
    "fullName": "东风汽车股份有限公司",
    "enName": "Dongfeng Automobile Co.,Ltd.",
    "cnspell": "dfgf",
    "market": "主板",
    "exchange": "SSE",
    "currType": "CNY",
    "listStatus": "L",
    "listDate": "933004800000",  // 这是时间戳，处理时请先转成long
    "isHs": "H",  // 是否沪深港通标的，N否 H沪股通 S深股通
    "actName": "东风汽车有限公司",  // 实控人名称
    "actEntType": "中央国企"  // 实控人企业性质
}
```

以上参数中，只有stockInfoId, tsCode, symbol, name, cnspell五个参数是必定非空的，剩余的都有可能是null。


### 搜索股票

向`{{baseUrl}}/domestic/stock/search`发送GET请求，需要参数：

- query：查询关键词


返回格式示例：

```json
{
    "items": [
        {
            "tsCode": "600000.SH",	
            "symbol": "600000",			// 六位数股票代码
            "name": "浦发银行",
            "area": "上海",
            "industry": "银行"
        },
        ...
    ]
}
```


### 根据股票代码（不是六位数代码，是后面加上市场标识符的代码）和时间范围查询日线行情

向`{{baseUrl}}/domestic/stock/daily/ts_code`发送GET请求，需要参数：

- ts_code：需要查询的股票代码
- start_date_timestamp：查询起始日期（时分秒统一为00:00:00）的毫秒时间戳
- end_date_timestamp：查询结束日期（时分秒统一为00:00:00）的毫秒时间戳


返回格式示例：

```json
{
    "items": [
        {
            "stockDailyId": "60",
            "tsCode": "000061.SZ",
            "tradeDate": "1736092800000",
            "close": 6.64,
            "open": 6.6,
            "high": 6.7,
            "low": 6.42,
            "preClose": 6.64,
            "change": 0.0,
            "pctChg": 0.0,
            "vol": 84248.55,
            "amount": 55671.809
        },
        ...
    ]
}
```