# agentToolsShared package boundary

## In scope (A2)

| Package | Classes |
|---------|---------|
| `world.willfrog.agent.tools.dataset` | DatasetRegistry, DatasetWriter |
| `world.willfrog.agent.tools.market` | MarketDataTools |
| `world.willfrog.agent.tools.python` | PythonSandboxTools |
| `world.willfrog.agent.tools.rag` | RagTools |
| `world.willfrog.agent.tools.search` | SearchTools |
| `world.willfrog.agent.tools.router` | ToolRouter, ToolResultCacheService, ToolWeightedLimitService, PythonStaticPrecheckService |

## Dependencies

- `agentPlatformShared` for config/context/service platform types
- Dubbo API modules for market/rag/search/python tools
- Must not depend on `agentService` or `workflow/*` executors

## Out of scope (stay in agentService until P1)

- `spawnSubAgent` / `waitForSubAgent` tool specs (ReactTodoExecutor)
- `AgentToolCatalogService`, simple tool fast path
