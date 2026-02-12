package world.willfrog.agent.graph;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.agent.context.AgentContext;
import world.willfrog.agent.service.AgentEventService;
import world.willfrog.agent.service.AgentObservabilityService;
import world.willfrog.agent.service.AgentRunStateStore;
import world.willfrog.agent.tool.ToolRouter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class ParallelTaskExecutor {

    // 新占位符协议：${taskId.output.path}
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([A-Za-z0-9_-]+)\\.output(?:\\.([A-Za-z0-9_.-]+))?}");
    // 旧协议：继续识别并主动报错，避免静默透传到下游执行器。
    private static final Pattern LEGACY_RESULT_PATTERN = Pattern.compile("#[A-Za-z0-9_-]+\\.result");
    private static final Pattern LEGACY_MUSTACHE_PATTERN = Pattern.compile("\\{\\{[^}]+}}");
    private static final Pattern LEGACY_TASK_OUTPUT_PATTERN = Pattern.compile("\\btask\\.output\\b");

    private final ToolRouter toolRouter;
    private final SubAgentRunner subAgentRunner;
    private final AgentEventService eventService;
    private final AgentRunStateStore stateStore;
    private final ExecutorService parallelExecutor;
    private final ObjectMapper objectMapper;

    public Map<String, ParallelTaskResult> execute(ParallelPlan plan,
                                                   String runId,
                                                   String userId,
                                                   Set<String> toolWhitelist,
                                                   List<ToolSpecification> toolSpecifications,
                                                   int subAgentMaxSteps,
                                                   String context,
                                                   dev.langchain4j.model.chat.ChatLanguageModel model,
                                                   Map<String, ParallelTaskResult> existingResults,
                                                   String endpointName,
                                                   String endpointBaseUrl,
                                                   String modelName) {
        if (plan == null || plan.getTasks() == null) {
            return Map.of();
        }
        Map<String, ParallelTaskResult> results = new ConcurrentHashMap<>();
        if (existingResults != null && !existingResults.isEmpty()) {
            results.putAll(existingResults);
        }
        List<ParallelPlan.PlanTask> pending = plan.getTasks().stream()
                .filter(task -> !results.containsKey(task.getId()))
                .collect(Collectors.toCollection(ArrayList::new));

        while (!pending.isEmpty()) {
            if (!eventService.isRunnable(runId, userId)) {
                break;
            }
            boolean debugMode = AgentContext.isDebugMode();
            List<ParallelPlan.PlanTask> ready = pending.stream()
                    .filter(task -> depsSatisfied(task, results.keySet()))
                    .collect(Collectors.toList());
            debugLog("ready tasks resolved: runId={}, pending={}, ready={}, finished={}",
                    runId,
                    pending.stream().map(ParallelPlan.PlanTask::getId).collect(Collectors.toList()),
                    ready.stream().map(ParallelPlan.PlanTask::getId).collect(Collectors.toList()),
                    new ArrayList<>(results.keySet()));

            if (ready.isEmpty()) {
                eventService.append(runId, userId, "PARALLEL_EXECUTION_BLOCKED", Map.of(
                        "reason", "no_ready_tasks_possible_cycle",
                        "pending_task_ids", pending.stream().map(ParallelPlan.PlanTask::getId).collect(Collectors.toList()),
                        "finished_task_ids", new ArrayList<>(results.keySet())
                ));
                log.warn("No ready tasks found, possible cyclic deps.");
                break;
            }

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (ParallelPlan.PlanTask task : ready) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        AgentContext.setRunId(runId);
                        AgentContext.setUserId(userId);
                        AgentContext.setDebugMode(debugMode);
                        AgentContext.setPhase(AgentObservabilityService.PHASE_PARALLEL_EXECUTION);
                        debugLog("task execution started: runId={}, taskId={}, type={}, tool={}, rawArgs={}",
                                runId,
                                task.getId(),
                                nvl(task.getType()),
                                nvl(task.getTool()),
                                safeJson(task.getArgs()));
                        Map<String, Object> startPayload = new LinkedHashMap<>();
                        startPayload.put("task_id", task.getId());
                        startPayload.put("type", nvl(task.getType()));
                        startPayload.put("tool", nvl(task.getTool()));
                        eventService.append(runId, userId, "PARALLEL_TASK_STARTED", startPayload);

                        stateStore.markTaskStarted(runId, task);
                        String taskContext = buildContext(task, context, results);
                        ParallelTaskResult result = executeTask(
                                task,
                                runId,
                                userId,
                                toolWhitelist,
                                subAgentMaxSteps,
                                taskContext,
                                model,
                                results,
                                endpointName,
                                endpointBaseUrl,
                                modelName
                        );
                        results.put(task.getId(), result);
                        stateStore.saveTaskResult(runId, task.getId(), result);
                        Map<String, Object> finishPayload = new LinkedHashMap<>();
                        finishPayload.put("task_id", task.getId());
                        finishPayload.put("success", result.isSuccess());
                        finishPayload.put("output_preview", preview(result.getOutput()));
                        finishPayload.put("cache", result.getCache() == null ? Map.of() : result.getCache());
                        eventService.append(runId, userId, "PARALLEL_TASK_FINISHED", finishPayload);
                        debugLog("task execution finished: runId={}, taskId={}, success={}, cache={}, outputPreview={}",
                                runId,
                                task.getId(),
                                result.isSuccess(),
                                result.getCache() == null ? Map.of() : result.getCache(),
                                preview(result.getOutput()));
                    } catch (Exception e) {
                        log.warn("Parallel task execution failed: runId={}, taskId={}", runId, task.getId(), e);
                        ParallelTaskResult failed = ParallelTaskResult.builder()
                                .taskId(task.getId())
                                .type(nvl(task.getType()))
                                .success(false)
                                .error(e.getMessage())
                                .output(internalFailure(nvl(task.getTool()), "INTERNAL_ERROR", nvl(e.getMessage()), Map.of()))
                                .build();
                        results.put(task.getId(), failed);
                        stateStore.saveTaskResult(runId, task.getId(), failed);
                        eventService.append(runId, userId, "PARALLEL_TASK_FAILED_INTERNAL", Map.of(
                                "task_id", task.getId(),
                                "error", e.getClass().getSimpleName() + ": " + nvl(e.getMessage())
                        ));
                        debugLog("task execution internal error: runId={}, taskId={}, error={}",
                                runId, task.getId(), e.getClass().getSimpleName() + ": " + nvl(e.getMessage()));
                    } finally {
                        AgentContext.clear();
                    }
                }, parallelExecutor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            pending.removeAll(ready);
        }
        return results;
    }

    private ParallelTaskResult executeTask(ParallelPlan.PlanTask task,
                                           String runId,
                                           String userId,
                                           Set<String> toolWhitelist,
                                           int subAgentMaxSteps,
                                           String context,
                                           dev.langchain4j.model.chat.ChatLanguageModel model,
                                           Map<String, ParallelTaskResult> knownResults,
                                           String endpointName,
                                           String endpointBaseUrl,
                                           String modelName) {
        String type = task.getType() == null ? "" : task.getType().trim().toLowerCase();
        if ("tool".equals(type)) {
            NormalizeResult normalize = normalizeToolArgs(task, knownResults);
            if (!normalize.unresolvedPlaceholders().isEmpty()) {
                String msg = "Unresolved placeholders: " + String.join(",", normalize.unresolvedPlaceholders());
                debugLog("task unresolved placeholders: runId={}, taskId={}, tool={}, placeholders={}",
                        AgentContext.getRunId(), task.getId(), nvl(task.getTool()), normalize.unresolvedPlaceholders());
                return ParallelTaskResult.builder()
                        .taskId(task.getId())
                        .type("tool")
                        .success(false)
                        .output(internalFailure(nvl(task.getTool()), "UNRESOLVED_PLACEHOLDER", msg, Map.of(
                                "task_id", task.getId(),
                                "placeholders", normalize.unresolvedPlaceholders()
                        )))
                        .error(msg)
                        .cache(toolRouter.toEventCachePayload(null))
                        .build();
            }
            debugLog("task args normalized: runId={}, taskId={}, tool={}, normalizedArgs={}",
                    AgentContext.getRunId(), task.getId(), nvl(task.getTool()), safeJson(normalize.args()));

            ToolRouter.ToolInvocationResult invokeResult = toolRouter.invokeWithMeta(task.getTool(), normalize.args());
            String output = invokeResult.getOutput();
            return ParallelTaskResult.builder()
                    .taskId(task.getId())
                    .type("tool")
                    .success(invokeResult.isSuccess())
                    .output(output)
                    .cache(toolRouter.toEventCachePayload(invokeResult))
                    .build();
        }
        if ("sub_agent".equals(type)) {
            Map<String, Object> seedArgs = resolveSubAgentSeedArgs(task, knownResults);
            SubAgentRunner.SubAgentRequest req = SubAgentRunner.SubAgentRequest.builder()
                    .runId(runId)
                    .userId(userId)
                    .taskId(task.getId())
                    .goal(task.getGoal())
                    .context(context)
                    .seedArgs(seedArgs)
                    .toolWhitelist(toolWhitelist)
                    .toolSpecifications(toolSpecifications)
                    .maxSteps(task.getMaxSteps() != null ? Math.min(task.getMaxSteps(), subAgentMaxSteps) : subAgentMaxSteps)
                    .endpointName(endpointName)
                    .endpointBaseUrl(endpointBaseUrl)
                    .modelName(modelName)
                    .build();
            SubAgentRunner.SubAgentResult subResult = subAgentRunner.run(req, model);
            return ParallelTaskResult.builder()
                    .taskId(task.getId())
                    .type("sub_agent")
                    .success(subResult.isSuccess())
                    .output(subResult.getAnswer())
                    .error(subResult.getError())
                    .cache(toolRouter.toEventCachePayload(null))
                    .build();
        }
        return ParallelTaskResult.builder()
                .taskId(task.getId())
                .type(type)
                .success(false)
                .error("unsupported task type")
                .output(internalFailure(nvl(task.getTool()), "UNSUPPORTED_TASK_TYPE", "Unsupported task type", Map.of("type", type)))
                .cache(toolRouter.toEventCachePayload(null))
                .build();
    }

    private boolean depsSatisfied(ParallelPlan.PlanTask task, Set<String> done) {
        List<String> deps = task.getDependsOn();
        if (deps == null || deps.isEmpty()) {
            return true;
        }
        return done.containsAll(deps);
    }

    private Map<String, Object> safeArgs(Map<String, Object> args) {
        return args == null ? Collections.emptyMap() : args;
    }

    private Map<String, Object> resolveSubAgentSeedArgs(ParallelPlan.PlanTask task,
                                                        Map<String, ParallelTaskResult> knownResults) {
        Map<String, Object> args = new LinkedHashMap<>(safeArgs(task.getArgs()));
        if (args.isEmpty()) {
            return Map.of();
        }

        String tool = nvl(task.getTool());
        if (!tool.isBlank()) {
            applyCommonAliases(tool, args);
        }

        List<String> deps = task.getDependsOn() == null ? List.of() : task.getDependsOn();
        Map<String, JsonNode> depOutputs = new LinkedHashMap<>();
        for (String dep : deps) {
            ParallelTaskResult result = knownResults == null ? null : knownResults.get(dep);
            depOutputs.put(dep, parseOutputJson(result == null ? "" : result.getOutput()));
        }

        Object resolved = resolveValue(args, depOutputs);
        Map<String, Object> resolvedArgs = toMap(resolved);
        if ("executePython".equals(tool)) {
            resolvedArgs = normalizeExecutePythonArgs(resolvedArgs, depOutputs);
        }
        return resolvedArgs;
    }

    private NormalizeResult normalizeToolArgs(ParallelPlan.PlanTask task,
                                              Map<String, ParallelTaskResult> knownResults) {
        Map<String, Object> args = new LinkedHashMap<>(safeArgs(task.getArgs()));
        String tool = nvl(task.getTool());

        // 第一步：把常见别名参数归一为工具标准参数名，减少模型输出波动造成的失败。
        applyCommonAliases(tool, args);

        List<String> deps = task.getDependsOn() == null ? List.of() : task.getDependsOn();
        Map<String, JsonNode> depOutputs = new LinkedHashMap<>();
        for (String dep : deps) {
            ParallelTaskResult result = knownResults == null ? null : knownResults.get(dep);
            depOutputs.put(dep, parseOutputJson(result == null ? "" : result.getOutput()));
        }

        Object resolved = resolveValue(args, depOutputs);
        Map<String, Object> resolvedArgs = toMap(resolved);

        if ("executePython".equals(tool)) {
            // Python 工具额外做 dataset_id 推导与代码路径重写。
            resolvedArgs = normalizeExecutePythonArgs(resolvedArgs, depOutputs);
        }

        List<String> unresolved = new ArrayList<>();
        collectUnresolvedPlaceholders(resolvedArgs, unresolved);

        return new NormalizeResult(resolvedArgs, unresolved);
    }

    private void applyCommonAliases(String tool, Map<String, Object> args) {
        if ("searchIndex".equals(tool) || "searchStock".equals(tool) || "searchFund".equals(tool)) {
            String keyword = firstNonBlank(args.get("keyword"), args.get("query"), args.get("q"), args.get("name"), args.get("arg0"));
            if (!keyword.isBlank()) {
                args.put("keyword", keyword);
            }
            return;
        }

        if ("getIndexDaily".equals(tool) || "getStockDaily".equals(tool) || "getStockInfo".equals(tool) || "getIndexInfo".equals(tool)) {
            String tsCode = firstNonBlank(args.get("tsCode"), args.get("ts_code"), args.get("code"), args.get("stock_code"), args.get("index_code"), args.get("arg0"));
            if (!tsCode.isBlank()) {
                args.put("tsCode", tsCode);
            }
            if ("getIndexDaily".equals(tool) || "getStockDaily".equals(tool)) {
                String startDateStr = compactDate(firstNonBlank(args.get("startDateStr"), args.get("startDate"), args.get("start_date"), args.get("arg1")));
                String endDateStr = compactDate(firstNonBlank(args.get("endDateStr"), args.get("endDate"), args.get("end_date"), args.get("arg2")));
                if (!startDateStr.isBlank()) {
                    args.put("startDateStr", startDateStr);
                }
                if (!endDateStr.isBlank()) {
                    args.put("endDateStr", endDateStr);
                }
            }
            return;
        }

        if ("executePython".equals(tool)) {
            String code = firstNonBlank(args.get("code"), args.get("arg0"));
            if (!code.isBlank()) {
                args.put("code", code);
            }
            String datasetId = firstNonBlank(args.get("dataset_id"), args.get("datasetId"), args.get("arg1"));
            if (!datasetId.isBlank()) {
                args.put("dataset_id", datasetId);
            }
            String datasetIds = firstNonBlank(args.get("dataset_ids"), args.get("datasetIds"), args.get("datasets"), args.get("dataset_refs"), args.get("datasetRefs"), args.get("arg2"));
            if (!datasetIds.isBlank()) {
                args.put("dataset_ids", datasetIds);
            }
        }
    }

    private Map<String, Object> normalizeExecutePythonArgs(Map<String, Object> args,
                                                           Map<String, JsonNode> depOutputs) {
        LinkedHashSet<String> depDatasetIds = new LinkedHashSet<>();
        for (JsonNode output : depOutputs.values()) {
            depDatasetIds.addAll(extractDatasetIds(output));
        }

        String datasetId = normalizeDatasetId(firstNonBlank(args.get("dataset_id"), args.get("datasetId"), args.get("arg1")));
        if (datasetId.isBlank() && !depDatasetIds.isEmpty()) {
            datasetId = depDatasetIds.iterator().next();
        }

        LinkedHashSet<String> allDatasetIds = new LinkedHashSet<>();
        allDatasetIds.addAll(parseDatasetIds(args.get("dataset_ids")));
        allDatasetIds.addAll(parseDatasetIds(args.get("datasetIds")));
        allDatasetIds.addAll(parseDatasetIds(args.get("datasets")));
        allDatasetIds.addAll(parseDatasetIds(args.get("dataset_refs")));
        allDatasetIds.addAll(parseDatasetIds(args.get("datasetRefs")));
        allDatasetIds.addAll(depDatasetIds);

        if (!datasetId.isBlank()) {
            args.put("dataset_id", datasetId);
            allDatasetIds.remove(datasetId);
        }
        if (!allDatasetIds.isEmpty()) {
            args.put("dataset_ids", String.join(",", allDatasetIds));
        }
        debugLog("executePython dataset normalization: runId={}, primaryDatasetId={}, extraDatasetIds={}",
                AgentContext.getRunId(), datasetId, allDatasetIds);

        String code = firstNonBlank(args.get("code"), args.get("arg0"));
        if (!code.isBlank()) {
            String rewritten = rewriteCodeDatasetPath(code, datasetId, allDatasetIds);
            rewritten = rewriteCodeDatasetAccess(rewritten, datasetId, allDatasetIds);
            args.put("code", rewritten);
            if (!rewritten.equals(code)) {
                debugLog("executePython code rewritten: runId={}, beforePreview={}, afterPreview={}",
                        AgentContext.getRunId(), preview(code), preview(rewritten));
            }
        }

        return args;
    }

    private Object resolveValue(Object input, Map<String, JsonNode> depOutputs) {
        if (input == null) {
            return null;
        }

        if (input instanceof Map<?, ?> raw) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                out.put(String.valueOf(entry.getKey()), resolveValue(entry.getValue(), depOutputs));
            }
            return out;
        }

        if (input instanceof List<?> rawList) {
            List<Object> out = new ArrayList<>();
            for (Object item : rawList) {
                out.add(resolveValue(item, depOutputs));
            }
            return out;
        }

        if (!(input instanceof String text) || text.isBlank()) {
            return input;
        }

        Matcher fullMatcher = PLACEHOLDER_PATTERN.matcher(text.trim());
        if (fullMatcher.matches()) {
            // 纯占位符场景保持原始类型（对象/数组/数字），不要强转成字符串。
            Object resolved = resolvePlaceholder(fullMatcher.group(1), fullMatcher.group(2), depOutputs);
            return resolved == null ? text : resolved;
        }

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        boolean found = false;
        while (matcher.find()) {
            found = true;
            // 混合文本场景用字符串内联替换。
            Object resolved = resolvePlaceholder(matcher.group(1), matcher.group(2), depOutputs);
            String replacement = resolved == null ? matcher.group(0) : stringifyInline(resolved);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        if (!found) {
            return text;
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private Object resolvePlaceholder(String taskId,
                                      String rawPath,
                                      Map<String, JsonNode> depOutputs) {
        JsonNode root = depOutputs.get(taskId);
        if (root == null || root.isMissingNode() || root.isNull()) {
            return null;
        }

        if (rawPath == null || rawPath.isBlank()) {
            return jsonNodeToObject(root);
        }

        JsonNode current = root;
        String[] parts = rawPath.split("\\.");
        for (String part : parts) {
            String token = nvl(part).trim();
            if (token.isBlank()) {
                return null;
            }
            if (current.isArray()) {
                if (!token.matches("\\d+")) {
                    return null;
                }
                int idx = Integer.parseInt(token);
                if (idx < 0 || idx >= current.size()) {
                    return null;
                }
                current = current.get(idx);
                continue;
            }
            if (!current.isObject()) {
                return null;
            }
            current = current.get(token);
            if (current == null || current.isMissingNode() || current.isNull()) {
                return null;
            }
        }
        return jsonNodeToObject(current);
    }

    private JsonNode parseOutputJson(String output) {
        if (output == null || output.isBlank()) {
            return objectMapper.getNodeFactory().missingNode();
        }
        try {
            return objectMapper.readTree(output);
        } catch (Exception e) {
            return objectMapper.getNodeFactory().missingNode();
        }
    }

    private Map<String, Object> toMap(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return out;
        }
        return new LinkedHashMap<>();
    }

    private List<String> extractDatasetIds(JsonNode output) {
        if (output == null || !output.path("ok").asBoolean(false)) {
            return List.of();
        }
        JsonNode data = output.path("data");
        if (!data.isObject()) {
            return List.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        String datasetId = normalizeDatasetId(data.path("dataset_id").asText(""));
        if (!datasetId.isBlank()) {
            ids.add(datasetId);
        }
        JsonNode datasetIdsNode = data.path("dataset_ids");
        if (datasetIdsNode.isArray()) {
            for (JsonNode item : datasetIdsNode) {
                String id = normalizeDatasetId(item.asText(""));
                if (!id.isBlank()) {
                    ids.add(id);
                }
            }
        } else if (datasetIdsNode.isTextual()) {
            ids.addAll(parseDatasetIds(datasetIdsNode.asText("")));
        }
        return new ArrayList<>(ids);
    }

    private LinkedHashSet<String> parseDatasetIds(Object raw) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (raw == null) {
            return ids;
        }

        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                String id = normalizeDatasetId(String.valueOf(item));
                if (!id.isBlank()) {
                    ids.add(id);
                }
            }
            return ids;
        }

        String text = nvl(String.valueOf(raw)).trim();
        if (text.isBlank()) {
            return ids;
        }
        if (text.startsWith("[") && text.endsWith("]")) {
            text = text.substring(1, text.length() - 1);
        }
        for (String part : text.split(",")) {
            String token = nvl(part).trim();
            if (token.startsWith("\"") && token.endsWith("\"") && token.length() >= 2) {
                token = token.substring(1, token.length() - 1).trim();
            }
            String id = normalizeDatasetId(token);
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
        return ids;
    }

    private String normalizeDatasetId(String datasetId) {
        String id = nvl(datasetId).trim();
        if (id.isBlank()) {
            return "";
        }
        if (!id.matches("[A-Za-z0-9._-]+")) {
            return "";
        }
        return id;
    }

    private String rewriteCodeDatasetPath(String code,
                                          String primaryDatasetId,
                                          Set<String> allDatasetIds) {
        String rewritten = code;
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (primaryDatasetId != null && !primaryDatasetId.isBlank()) {
            ids.add(primaryDatasetId);
        }
        ids.addAll(allDatasetIds == null ? Set.of() : allDatasetIds);
        for (String datasetId : ids) {
            if (datasetId == null || datasetId.isBlank()) {
                continue;
            }
            String canonicalPath = "/sandbox/input/" + datasetId + "/" + datasetId + ".csv";
            rewritten = rewritten.replace("pd.read_csv('" + datasetId + "')", "pd.read_csv('" + canonicalPath + "')");
            rewritten = rewritten.replace("pd.read_csv(\"" + datasetId + "\")", "pd.read_csv(\"" + canonicalPath + "\")");
        }
        return rewritten;
    }

    private String rewriteCodeDatasetAccess(String code,
                                            String primaryDatasetId,
                                            Set<String> allDatasetIds) {
        String rewritten = nvl(code);
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (primaryDatasetId != null && !primaryDatasetId.isBlank()) {
            ids.add(primaryDatasetId);
        }
        ids.addAll(allDatasetIds == null ? Set.of() : allDatasetIds);

        for (String datasetId : ids) {
            if (datasetId == null || datasetId.isBlank()) {
                continue;
            }
            String canonicalCsv = "/sandbox/input/" + datasetId + "/" + datasetId + ".csv";
            String legacyCsv = "/sandbox/input/" + datasetId + "/data.csv";
            String legacyParquet = "/sandbox/input/" + datasetId + "/data.parquet";
            rewritten = rewritten.replace("get_dataset('" + datasetId + "')", "pd.read_csv('" + canonicalCsv + "')");
            rewritten = rewritten.replace("get_dataset(\"" + datasetId + "\")", "pd.read_csv(\"" + canonicalCsv + "\")");
            rewritten = rewritten.replace("load_dataset('" + datasetId + "')", "pd.read_csv('" + canonicalCsv + "')");
            rewritten = rewritten.replace("load_dataset(\"" + datasetId + "\")", "pd.read_csv(\"" + canonicalCsv + "\")");
            rewritten = rewritten.replace(legacyCsv, canonicalCsv);
            rewritten = rewritten.replace(legacyParquet, canonicalCsv);
        }

        rewritten = rewritten.replace("/{dataset_id}/data.csv", "/{dataset_id}/{dataset_id}.csv");
        rewritten = rewritten.replace("/{dataset_id}/data.parquet", "/{dataset_id}/{dataset_id}.csv");
        rewritten = rewritten.replace("pd.read_parquet(", "pd.read_csv(");

        // Inject helper function for get_dataset if still used and not defined
        if (rewritten.contains("get_dataset(") && !rewritten.contains("def get_dataset(")) {
            String helper = "import pandas as pd\n"
                    + "def get_dataset(dataset_id):\n"
                    + "    dataset_id = str(dataset_id).strip()\n"
                    + "    return pd.read_csv(f\"/sandbox/input/{dataset_id}/{dataset_id}.csv\")\n\n";
            rewritten = helper + rewritten;
        }
        // Inject helper function for load_dataset if still used and not defined
        if (rewritten.contains("load_dataset(") && !rewritten.contains("def load_dataset(")) {
            String helper = "import pandas as pd\n"
                    + "def load_dataset(dataset_id):\n"
                    + "    dataset_id = str(dataset_id).strip()\n"
                    + "    return pd.read_csv(f\"/sandbox/input/{dataset_id}/{dataset_id}.csv\")\n\n";
            rewritten = helper + rewritten;
        }
        return rewritten;
    }

    private void collectUnresolvedPlaceholders(Object value, List<String> unresolved) {
        if (value == null) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                collectUnresolvedPlaceholders(item, unresolved);
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                collectUnresolvedPlaceholders(item, unresolved);
            }
            return;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            return;
        }
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        while (matcher.find()) {
            String placeholder = matcher.group(0);
            if (!unresolved.contains(placeholder)) {
                unresolved.add(placeholder);
            }
        }
        // 历史语法统一纳入未解析列表，强制走失败分支，避免带着脏参数进入 sandbox。
        collectLegacyTokens(text, LEGACY_RESULT_PATTERN, unresolved);
        collectLegacyTokens(text, LEGACY_MUSTACHE_PATTERN, unresolved);
        collectLegacyTokens(text, LEGACY_TASK_OUTPUT_PATTERN, unresolved);
    }

    private void collectLegacyTokens(String text, Pattern pattern, List<String> unresolved) {
        if (text == null || text.isBlank() || pattern == null) {
            return;
        }
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String token = matcher.group(0);
            if (!unresolved.contains(token)) {
                unresolved.add(token);
            }
        }
    }

    private Object jsonNodeToObject(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        try {
            return objectMapper.convertValue(node, new TypeReference<Object>() {
            });
        } catch (Exception e) {
            return node.asText("");
        }
    }

    private String stringifyInline(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String internalFailure(String tool,
                                   String code,
                                   String message,
                                   Map<String, Object> details) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", false);
        payload.put("tool", tool);
        payload.put("data", Map.of());
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", nvl(code));
        err.put("message", nvl(message));
        err.put("details", details == null ? Map.of() : details);
        payload.put("error", err);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

    private String firstNonBlank(Object... values) {
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            String s = String.valueOf(value).trim();
            if (!s.isBlank()) {
                return s;
            }
        }
        return "";
    }

    private String compactDate(String raw) {
        if (raw == null) {
            return "";
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() == 8 || digits.length() == 13) {
            return digits;
        }
        return raw.trim();
    }

    private String buildContext(ParallelPlan.PlanTask task, String baseContext, Map<String, ParallelTaskResult> results) {
        if (task.getDependsOn() == null || task.getDependsOn().isEmpty()) {
            return baseContext;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(baseContext == null ? "" : baseContext);
        sb.append("\n依赖结果:\n");
        for (String dep : task.getDependsOn()) {
            ParallelTaskResult result = results.get(dep);
            if (result == null) {
                continue;
            }
            sb.append(dep).append(": ")
                    .append(result.isSuccess() ? "ok" : "failed")
                    .append(" -> ")
                    .append(preview(result.getOutput()))
                    .append("\n");
        }
        return sb.toString();
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() > 300) {
            return text.substring(0, 300);
        }
        return text;
    }

    private String safeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private void debugLog(String pattern, Object... args) {
        if (!AgentContext.isDebugMode()) {
            return;
        }
        log.info("[agent-debug] " + pattern, args);
    }

    private String nvl(String text) {
        return text == null ? "" : text;
    }

    private record NormalizeResult(Map<String, Object> args, List<String> unresolvedPlaceholders) {
    }
}
