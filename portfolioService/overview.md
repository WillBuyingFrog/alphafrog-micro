根据前期的调研思考交流，目前我们对投资组合服务有如下的功能考虑：

前置知识1:投资者确实既可以持有股票，也可以持有场内交易基金（ETFs）。有关场内交易基金，请参阅`FundInfo.java` 和 `FundNav.java`。`FundInfo` 中的 `market` 字段（当值为 "E" 时表示场内交易）和 `FundNav` 中的 `unitNav`（单位净值，可视为价格）对于整合基金到投资组合中至关重要。

以下是当前版本的具体代码结构设计：

**一、 数据模型 (POJOs / JPA Entities)**

1.  **`Portfolio` **:
    *   包含 `id`, `userId`, `name`, 以及 `List<PortfolioHolding> holdings`。

2.  **`PortfolioHolding` **:
    *   **用途**: 代表投资组合中的单一持仓，可以是股票或场内基金。
    *   **主要属性**:
        *   `id` (Long): 主键。
        *   `assetIdentifier` (String): 资产的唯一代码。对于股票，这可能是 `tsCode`；对于基金，这也将是 `tsCode` (与 `FundInfo` 和 `FundNav` 中的 `tsCode` 对应)。
        *   `assetType` (Enum: `AssetType`): 用于区分资产类型。例如，`AssetType.STOCK`, `AssetType.FUND_ETF`。
        *   `quantity` (BigDecimal): 持有数量。
        *   `averageCostPrice` (BigDecimal): 平均持仓成本。
        *   `portfolio` (`Portfolio`): 多对一关联。
    *   **说明**: 这个实体将通过 `assetIdentifier` 和 `assetType` 来泛指任何可投资资产。

3.  **`AssetType` (枚举类)**:
    *   **用途**: 定义投资组合可以持有的资产类型。
    *   **枚举值**: `STOCK`, `FUND_ETF`（后续可拓展）。

4.  **`StockDaily` (如前所述，用于股票)**:
    *   包含 `stockDailyId` (ID), `tsCode` (股票代码), `tradeDate` (交易日期), `open` (开盘价), `high` (最高价), `low` (最低价), `close` (收盘价), `preClose` (昨日收盘价), `change` (涨跌额), `pctChg` (涨跌幅), `vol` (成交量), `amount` (成交额) 等。

5.  **`FundInfo`**:
    *   用于获取基金的基本信息，特别是通过 `market` 字段筛选出场内基金（ETF）。

6.  **`FundNav` (用户已提供)**:
    *   用于获取基金的每日净值 (`unitNav`)，这将作为基金的价格。我们需要当日和前一日的 `unitNav` 来计算日涨跌。

7.  **`HoldingPerformance` (调整)**:
    *   **用途**: 封装投资组合中单个持仓（股票或基金）当日的表现详情。
    *   **主要属性**:
        *   `assetIdentifier` (String): 资产代码。
        *   `assetType` (`AssetType`): 资产类型。
        *   `assetName` (String): 资产名称 (例如股票名称或基金简称，从 `StockInfo` 或 `FundInfo` 获取)。
        *   `quantity` (BigDecimal): 持有资产的数量。
        *   `averageCostPrice` (BigDecimal): 每单位资产的平均成本价格。
        *   `currentPrice` (BigDecimal): 每单位资产的当前市场价格。
        *   `currentMarketValue` (BigDecimal): 当前持仓总市值 (计算公式: `quantity * currentPrice`)。
        *   `dailyReturn` (BigDecimal): 当日的货币化收益或损失。
        *   `dailyReturnRate` (BigDecimal): 当日的百分比收益或损失，此数据用于对表现最好和最差的资产进行排名。
        *   `profitAndLossContribution` (BigDecimal): (在 `HoldingPerformanceDto.java` 中对应 `totalProfitAndLoss` 字段) 自购入持仓以来的累计总盈亏 (计算公式: `currentMarketValue - initialCost`)。

8.  **`PortfolioDailySummary` **:
    *   **用途**: 封装投资组合在特定日期的每日表现摘要。它由 `PortfolioSummaryService` 生成，可提供投资组合整体表现的快照。
    *   **主要属性**:
        *   `portfolioId` (Long): 投资组合的唯一标识符。
        *   `portfolioName` (String): 投资组合的名称。
        *   `date` (LocalDate): 此摘要所对应的日期。
        *   `totalMarketValue` (BigDecimal): 投资组合在摘要日期的总市场价值。
        *   `dailyReturn` (BigDecimal): 投资组合当日的绝对回报金额。
        *   `dailyReturnRate` (BigDecimal): 投资组合当日的回报率（百分比）。
        *   `cumulativeReturnRate` (BigDecimal): 从初始投资开始计算的累计回报率。
        *   `holdingPerformances` (List<`HoldingPerformanceDto`>): 包含投资组合中每个单独持仓（无论是股票还是基金）的详细表现数据。
        *   `topGainers` (List<`HoldingPerformanceDto`>): 当日表现最好的N个持仓列表（可包含股票或基金）。
        *   `topLosers` (List<`HoldingPerformanceDto`>): 当日表现最差的N个持仓列表（可包含股票或基金）。

**二、 功能类 (Service / Component)**

1.  **`PortfolioService`**:
    *   主要负责 `Portfolio` (投资组合) 和 `PortfolioHolding` (持仓) 的核心业务逻辑，具体操作如下：
        *   **投资组合 (Portfolio) 管理**:
            *   `createPortfolio`: 创建新的投资组合。
            *   `getPortfolioById`: 根据ID获取投资组合基本信息。
            *   `getPortfolioWithHoldingsById`: 根据ID获取投资组合及其所有持仓的详细信息。
            *   `getPortfoliosByUserId`: 根据用户ID获取该用户的所有投资组合列表。
            *   `updatePortfolio`: 更新已有的投资组合信息。
            *   `deletePortfolio`: 删除指定的投资组合（注意：相关的持仓删除逻辑需要根据业务规则处理，例如级联删除或先删除持仓）。
        *   **持仓 (PortfolioHolding) 管理**:
            *   `addHoldingToPortfolio`: 向指定的投资组合中添加新的持仓。
            *   `updateHoldingInPortfolio`: 更新投资组合中已有的持仓信息。
            *   `removeHoldingFromPortfolio`: 从投资组合中移除指定的持仓。
            *   `getHoldingsByPortfolioId`: 根据投资组合ID获取其下所有持仓的列表。
            *   `getHoldingById`: 根据ID获取单个持仓的详细信息。

2.  **`AssetPriceService`**:
    *   **职责**: 负责为不同类型的资产（股票、场内基金）获取价格信息。
    *   **主要功能**:
        *   `getAssetPriceInfo(assetIdentifier, assetType, date)`: 根据资产标识符和类型，获取指定日期的价格信息（当前价格和前一交易日价格）。
            *   当`assetType`为`STOCK`时，查询股票日线数据表获取收盘价。
            *   当`assetType`为`FUND_ETF`时，查询`FundNav`表获取单位净值(`unitNav`)。
        *   `getMultipleAssetPriceInfo(assetIdentifiersWithTypes, date)`: 批量获取多个持仓资产在指定日期的价格信息，返回包含所有资产价格的Map。

3.  **`AssetInfoService`**:
    *   **职责**: 获取资产的基础信息，如名称等元数据。
    *   **主要功能**:
        *   `getAssetInfo(assetIdentifier, assetType)`: 根据资产标识符和类型获取资产信息。
            *   当`assetType`为`STOCK`时，查询股票信息表获取股票名称等信息。
            *   当`assetType`为`FUND_ETF`时，查询`FundInfo`表获取基金名称等信息。
        *   `getMultipleAssetInfo(assetIdentifiersWithTypes)`: 批量获取多个资产的基础信息，返回包含所有资产信息的Map。

4.  **`PortfolioCalculatorService`**:
    *   **职责**: 执行投资组合表现计算，包括收益率、市值等财务指标计算。
    *   **主要功能**:
        *   `calculateHoldingPerformance(holding, assetInfo, priceInfo)`: 计算单个持仓的表现指标，包括当前市值、日收益、日收益率和总收益等。
        *   `calculatePortfolioInitialCost(holdings)`: 计算投资组合的初始成本总额。
        *   `calculatePortfolioMarketValue(holdings, assetPrices)`: 根据当前价格计算投资组合的总市值。

5.  **`PortfolioSummaryService`** (整合服务):
    *   **职责**: 协调上述服务，生成投资组合的日度表现摘要。
    *   **主要功能**: 
        *   `generateDailySummary(portfolioId, date)`: 生成特定日期的投资组合表现摘要，包括以下步骤：
            1. 获取投资组合及其持仓列表
            2. 使用`AssetPriceService`获取所有持仓资产的价格信息
            3. 使用`AssetInfoService`获取所有持仓资产的名称信息
            4. 调用`PortfolioCalculatorService`计算每个持仓的表现指标
            5. 计算投资组合级别的指标（总市值、日收益率、累计收益率等）
            6. 确定表现最好和最差的资产
            7. 汇总并返回完整的`PortfolioDailySummaryDto`

6.  **`MarketAlertIntegrationService` (可能需要调整)**:
    *   **职责**: 与"市场异动监测服务"集成。
    *   **主要功能**:
        *   需要确认该异动服务是否支持基金，如果支持，则此服务也需要能处理基金的预警。

7.  **`PortfolioDigestService` (编排服务 - 调整)**:
    *   **职责**: 协调以上服务，生成最终摘要。
    *   **主要功能**: `generateDailySummary(portfolioId, date)`
        1.  获取 `Portfolio` 及其 `PortfolioHolding` 列表。
        2.  遍历 `PortfolioHolding`，对每个 `holding`：
            *   使用 `AssetPriceService` 获取其在 `date` 和 `date-1` 的价格信息 (基于 `holding.assetIdentifier` 和 `holding.assetType`)。
            *   使用 `AssetInfoService` 获取其名称。
        3.  调用 `PortfolioCalculatorService` 进行各项计算，传入从 `AssetPriceService` 获得的价格。
        4.  (如果适用) 调用 `MarketAlertIntegrationService` 获取预警。
        5.  组装 `PortfolioDailySummary`。

**三、 API 层 (Controller)**

1.  **`PortfolioSummaryController` (保持不变)**:
    *   API 接口 `GET /api/portfolio/{portfolio_id}/summary/daily?date={trade_date}` 保持不变。控制器将调用更新后的 `PortfolioDigestService`。

**总结一下关键的改动点：**

*   **`PortfolioHolding` 的泛化**: 引入 `assetType` 字段，使其能够代表多种资产。
*   **`AssetPriceService`**: 创建一个统一的服务来获取不同类型资产的价格，隐藏底层数据源的差异。
*   **`AssetInfoService`**: 创建一个服务来获取资产的描述性信息（如名称）。
*   **利用 `FundInfo` 和 `FundNav`**: 将用户提供的基金实体整合到价格和信息获取流程中。

这样调整后，你的服务将更加灵活和可扩展，未来如果需要支持更多类型的投资产品（如债券等），也可以在此基础上进行扩展。

接下来，你可以开始着手定义新的 `AssetType` 枚举，并修改 `PortfolioHolding` 实体，然后逐步实现上述调整后的各个服务类。
