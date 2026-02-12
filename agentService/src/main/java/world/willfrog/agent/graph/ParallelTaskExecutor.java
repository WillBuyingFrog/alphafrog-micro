package world.willfrog.agent.graph;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([A-Za-z0-9_-]+)\\.output(?:\\.([A-Za-z0-9_.-]+))?}");

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
            List<ParallelPlan.PlanTask> ready = pending.stream()
                    .filter(task -> depsSatisfied(task, results.keySet()))
                    .collect(Collectors.toList());

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
                        AgentContext.setPhase(AgentObservabilityService.PHASE_PARALLEL_EXECUTION);
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
            SubAgentRunner.SubAgentRequest req = SubAgentRunner.SubAgentRequest.builder()
                    .runId(runId)
                    .userId(userId)
                    .taskId(task.getId())
                    .goal(task.getGoal())
                    .context(context)
                    .toolWhitelist(toolWhitelist)
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

    private NormalizeResult normalizeToolArgs(ParallelPlan.PlanTask task,
                                              Map<String, ParallelTaskResult> knownResults) {
        Map<String, Object> args = new LinkedHashMap<>(safeArgs(task.getArgs()));
        String tool = nvl(task.getTool());

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

        String code = firstNonBlank(args.get("code"), args.get("arg0"));
        if (!code.isBlank()) {
            String rewritten = rewriteCodeDatasetPath(code, datasetId, allDatasetIds);
            args.put("code", rewritten);
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
            Object resolved = resolvePlaceholder(fullMatcher.group(1), fullMatcher.group(2), depOutputs);
            return resolved == null ? text : resolved;
        }

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        boolean found = false;
        while (matcher.find()) {
            found = true;
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

    private String nvl(String text) {
        return text == null ? "" : text;
    }

    private record NormalizeResult(Map<String, Object> args, List<String> unresolvedPlaceholders) {
    }
}
