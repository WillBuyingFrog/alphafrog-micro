package world.willfrog.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "agent.llm")
public class AgentLlmProperties {

    private String defaultEndpoint;
    private String defaultModel;
    private Map<String, Endpoint> endpoints = new HashMap<>();
    private List<String> models = new ArrayList<>();
    private Map<String, ModelMetadata> modelMetadata = new HashMap<>();
    private Runtime runtime = new Runtime();
    private Observability observability = new Observability();
    private Prompts prompts = new Prompts();

    public String getDefaultEndpoint() {
        return defaultEndpoint;
    }

    public void setDefaultEndpoint(String defaultEndpoint) {
        this.defaultEndpoint = defaultEndpoint;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public Map<String, Endpoint> getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(Map<String, Endpoint> endpoints) {
        this.endpoints = endpoints == null ? new HashMap<>() : endpoints;
    }

    public List<String> getModels() {
        return models;
    }

    public void setModels(List<String> models) {
        this.models = models == null ? new ArrayList<>() : models;
    }

    public Map<String, ModelMetadata> getModelMetadata() {
        return modelMetadata;
    }

    public void setModelMetadata(Map<String, ModelMetadata> modelMetadata) {
        this.modelMetadata = modelMetadata == null ? new HashMap<>() : modelMetadata;
    }

    public Prompts getPrompts() {
        return prompts;
    }

    public void setPrompts(Prompts prompts) {
        this.prompts = prompts == null ? new Prompts() : prompts;
    }

    public Runtime getRuntime() {
        return runtime;
    }

    public void setRuntime(Runtime runtime) {
        this.runtime = runtime == null ? new Runtime() : runtime;
    }

    public Observability getObservability() {
        return observability;
    }

    public void setObservability(Observability observability) {
        this.observability = observability == null ? new Observability() : observability;
    }

    public static class Endpoint {
        private String baseUrl;
        private String apiKey;
        private String region;
        /**
         * 新配置支持在 endpoint 下声明模型元信息：
         * endpoint -> models -> modelId -> metadata。
         */
        private Map<String, ModelMetadata> models = new HashMap<>();

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public Map<String, ModelMetadata> getModels() {
            return models;
        }

        public void setModels(Map<String, ModelMetadata> models) {
            this.models = models == null ? new HashMap<>() : models;
        }
    }

    public static class ModelMetadata {
        private String displayName;
        private Double baseRate;
        private List<String> features = new ArrayList<>();
        private List<String> validProviders = new ArrayList<>();

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public Double getBaseRate() {
            return baseRate;
        }

        public void setBaseRate(Double baseRate) {
            this.baseRate = baseRate;
        }

        public List<String> getFeatures() {
            return features;
        }

        public void setFeatures(List<String> features) {
            this.features = features == null ? new ArrayList<>() : features;
        }

        public List<String> getValidProviders() {
            return validProviders;
        }

        public void setValidProviders(List<String> validProviders) {
            this.validProviders = validProviders == null ? new ArrayList<>() : validProviders;
        }
    }

    public static class Runtime {
        private Resume resume = new Resume();
        private Cache cache = new Cache();
        private Execution execution = new Execution();
        private Planning planning = new Planning();
        private Parallel parallel = new Parallel();
        private SubAgent subAgent = new SubAgent();
        private Judge judge = new Judge();
        private MultiTurn multiTurn = new MultiTurn();

        public Resume getResume() {
            return resume;
        }

        public void setResume(Resume resume) {
            this.resume = resume == null ? new Resume() : resume;
        }

        public Cache getCache() {
            return cache;
        }

        public void setCache(Cache cache) {
            this.cache = cache == null ? new Cache() : cache;
        }

        public Execution getExecution() {
            return execution;
        }

        public void setExecution(Execution execution) {
            this.execution = execution == null ? new Execution() : execution;
        }

        public Planning getPlanning() {
            return planning;
        }

        public void setPlanning(Planning planning) {
            this.planning = planning == null ? new Planning() : planning;
        }

        public Parallel getParallel() {
            return parallel;
        }

        public void setParallel(Parallel parallel) {
            this.parallel = parallel == null ? new Parallel() : parallel;
        }

        public SubAgent getSubAgent() {
            return subAgent;
        }

        public void setSubAgent(SubAgent subAgent) {
            this.subAgent = subAgent == null ? new SubAgent() : subAgent;
        }

        public Judge getJudge() {
            return judge;
        }

        public void setJudge(Judge judge) {
            this.judge = judge == null ? new Judge() : judge;
        }

        public MultiTurn getMultiTurn() {
            return multiTurn;
        }

        public void setMultiTurn(MultiTurn multiTurn) {
            this.multiTurn = multiTurn == null ? new MultiTurn() : multiTurn;
        }
    }

    public static class Resume {
        private Integer interruptedTtlDays;

        public Integer getInterruptedTtlDays() {
            return interruptedTtlDays;
        }

        public void setInterruptedTtlDays(Integer interruptedTtlDays) {
            this.interruptedTtlDays = interruptedTtlDays;
        }
    }

    public static class Cache {
        private String version;
        private Integer searchTtlSeconds;
        private Integer infoTtlSeconds;
        private Integer datasetTtlSeconds;

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public Integer getSearchTtlSeconds() {
            return searchTtlSeconds;
        }

        public void setSearchTtlSeconds(Integer searchTtlSeconds) {
            this.searchTtlSeconds = searchTtlSeconds;
        }

        public Integer getInfoTtlSeconds() {
            return infoTtlSeconds;
        }

        public void setInfoTtlSeconds(Integer infoTtlSeconds) {
            this.infoTtlSeconds = infoTtlSeconds;
        }

        public Integer getDatasetTtlSeconds() {
            return datasetTtlSeconds;
        }

        public void setDatasetTtlSeconds(Integer datasetTtlSeconds) {
            this.datasetTtlSeconds = datasetTtlSeconds;
        }
    }

    public static class Planning {
        private Integer candidatePlanCount;
        private Integer maxLocalReplans;
        private Integer maxTodos;
        private Integer autoSplitThreshold;
        private Double complexityPenaltyLambda;
        private StructuredOutput structuredOutput = new StructuredOutput();

        public Integer getCandidatePlanCount() {
            return candidatePlanCount;
        }

        public void setCandidatePlanCount(Integer candidatePlanCount) {
            this.candidatePlanCount = candidatePlanCount;
        }

        public Integer getMaxLocalReplans() {
            return maxLocalReplans;
        }

        public void setMaxLocalReplans(Integer maxLocalReplans) {
            this.maxLocalReplans = maxLocalReplans;
        }

        public Integer getMaxTodos() {
            return maxTodos;
        }

        public void setMaxTodos(Integer maxTodos) {
            this.maxTodos = maxTodos;
        }

        public Integer getAutoSplitThreshold() {
            return autoSplitThreshold;
        }

        public void setAutoSplitThreshold(Integer autoSplitThreshold) {
            this.autoSplitThreshold = autoSplitThreshold;
        }

        public Double getComplexityPenaltyLambda() {
            return complexityPenaltyLambda;
        }

        public void setComplexityPenaltyLambda(Double complexityPenaltyLambda) {
            this.complexityPenaltyLambda = complexityPenaltyLambda;
        }

        public StructuredOutput getStructuredOutput() {
            return structuredOutput;
        }

        public void setStructuredOutput(StructuredOutput structuredOutput) {
            this.structuredOutput = structuredOutput == null ? new StructuredOutput() : structuredOutput;
        }
    }

    public static class Execution {
        private String mode;
        private Integer maxToolCalls;
        private Integer maxToolCallsPerSubAgent;
        private Integer maxRetriesPerTodo;
        private Boolean staticPrecheckEnabled;
        private Integer maxStaticRecoveryRetries;
        private Integer maxRuntimeRecoveryRetries;
        private Integer maxSemanticRecoveryRetries;
        private Integer maxTotalRecoveryRetries;
        private String staticFixEndpoint;
        private String staticFixModel;
        private Double staticFixTemperature;
        private Boolean failFast;
        private String defaultExecutionMode;

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public Integer getMaxToolCalls() {
            return maxToolCalls;
        }

        public void setMaxToolCalls(Integer maxToolCalls) {
            this.maxToolCalls = maxToolCalls;
        }

        public Integer getMaxToolCallsPerSubAgent() {
            return maxToolCallsPerSubAgent;
        }

        public void setMaxToolCallsPerSubAgent(Integer maxToolCallsPerSubAgent) {
            this.maxToolCallsPerSubAgent = maxToolCallsPerSubAgent;
        }

        public Integer getMaxRetriesPerTodo() {
            return maxRetriesPerTodo;
        }

        public void setMaxRetriesPerTodo(Integer maxRetriesPerTodo) {
            this.maxRetriesPerTodo = maxRetriesPerTodo;
        }

        public Boolean getStaticPrecheckEnabled() {
            return staticPrecheckEnabled;
        }

        public void setStaticPrecheckEnabled(Boolean staticPrecheckEnabled) {
            this.staticPrecheckEnabled = staticPrecheckEnabled;
        }

        public Integer getMaxStaticRecoveryRetries() {
            return maxStaticRecoveryRetries;
        }

        public void setMaxStaticRecoveryRetries(Integer maxStaticRecoveryRetries) {
            this.maxStaticRecoveryRetries = maxStaticRecoveryRetries;
        }

        public Integer getMaxRuntimeRecoveryRetries() {
            return maxRuntimeRecoveryRetries;
        }

        public void setMaxRuntimeRecoveryRetries(Integer maxRuntimeRecoveryRetries) {
            this.maxRuntimeRecoveryRetries = maxRuntimeRecoveryRetries;
        }

        public Integer getMaxSemanticRecoveryRetries() {
            return maxSemanticRecoveryRetries;
        }

        public void setMaxSemanticRecoveryRetries(Integer maxSemanticRecoveryRetries) {
            this.maxSemanticRecoveryRetries = maxSemanticRecoveryRetries;
        }

        public Integer getMaxTotalRecoveryRetries() {
            return maxTotalRecoveryRetries;
        }

        public void setMaxTotalRecoveryRetries(Integer maxTotalRecoveryRetries) {
            this.maxTotalRecoveryRetries = maxTotalRecoveryRetries;
        }

        public String getStaticFixEndpoint() {
            return staticFixEndpoint;
        }

        public void setStaticFixEndpoint(String staticFixEndpoint) {
            this.staticFixEndpoint = staticFixEndpoint;
        }

        public String getStaticFixModel() {
            return staticFixModel;
        }

        public void setStaticFixModel(String staticFixModel) {
            this.staticFixModel = staticFixModel;
        }

        public Double getStaticFixTemperature() {
            return staticFixTemperature;
        }

        public void setStaticFixTemperature(Double staticFixTemperature) {
            this.staticFixTemperature = staticFixTemperature;
        }

        public Boolean getFailFast() {
            return failFast;
        }

        public void setFailFast(Boolean failFast) {
            this.failFast = failFast;
        }

        public String getDefaultExecutionMode() {
            return defaultExecutionMode;
        }

        public void setDefaultExecutionMode(String defaultExecutionMode) {
            this.defaultExecutionMode = defaultExecutionMode;
        }
    }

    public static class Parallel {
        private Integer maxParallelSearchQueries;
        private Integer maxParallelDailyQueries;

        public Integer getMaxParallelSearchQueries() {
            return maxParallelSearchQueries;
        }

        public void setMaxParallelSearchQueries(Integer maxParallelSearchQueries) {
            this.maxParallelSearchQueries = maxParallelSearchQueries;
        }

        public Integer getMaxParallelDailyQueries() {
            return maxParallelDailyQueries;
        }

        public void setMaxParallelDailyQueries(Integer maxParallelDailyQueries) {
            this.maxParallelDailyQueries = maxParallelDailyQueries;
        }
    }

    public static class SubAgent {
        private Boolean enabled;
        private String complexityThreshold;
        private Integer maxSteps;
        private StructuredOutput structuredOutput = new StructuredOutput();
        private Placeholder placeholder = new Placeholder();

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public String getComplexityThreshold() {
            return complexityThreshold;
        }

        public void setComplexityThreshold(String complexityThreshold) {
            this.complexityThreshold = complexityThreshold;
        }

        public Integer getMaxSteps() {
            return maxSteps;
        }

        public void setMaxSteps(Integer maxSteps) {
            this.maxSteps = maxSteps;
        }

        public StructuredOutput getStructuredOutput() {
            return structuredOutput;
        }

        public void setStructuredOutput(StructuredOutput structuredOutput) {
            this.structuredOutput = structuredOutput == null ? new StructuredOutput() : structuredOutput;
        }

        public Placeholder getPlaceholder() {
            return placeholder;
        }

        public void setPlaceholder(Placeholder placeholder) {
            this.placeholder = placeholder == null ? new Placeholder() : placeholder;
        }
    }

    public static class StructuredOutput {
        private Boolean enabled;
        private Integer maxAttempts;
        private Boolean strict;
        private Boolean failOnExhaustedRetries;
        private Boolean requireProviderParameters;
        private Boolean allowProviderFallbacks;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public Integer getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(Integer maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Boolean getStrict() {
            return strict;
        }

        public void setStrict(Boolean strict) {
            this.strict = strict;
        }

        public Boolean getFailOnExhaustedRetries() {
            return failOnExhaustedRetries;
        }

        public void setFailOnExhaustedRetries(Boolean failOnExhaustedRetries) {
            this.failOnExhaustedRetries = failOnExhaustedRetries;
        }

        public Boolean getRequireProviderParameters() {
            return requireProviderParameters;
        }

        public void setRequireProviderParameters(Boolean requireProviderParameters) {
            this.requireProviderParameters = requireProviderParameters;
        }

        public Boolean getAllowProviderFallbacks() {
            return allowProviderFallbacks;
        }

        public void setAllowProviderFallbacks(Boolean allowProviderFallbacks) {
            this.allowProviderFallbacks = allowProviderFallbacks;
        }
    }

    public static class Placeholder {
        private Boolean resolveStepAlias;
        private Boolean resolveTodoAlias;

        public Boolean getResolveStepAlias() {
            return resolveStepAlias;
        }

        public void setResolveStepAlias(Boolean resolveStepAlias) {
            this.resolveStepAlias = resolveStepAlias;
        }

        public Boolean getResolveTodoAlias() {
            return resolveTodoAlias;
        }

        public void setResolveTodoAlias(Boolean resolveTodoAlias) {
            this.resolveTodoAlias = resolveTodoAlias;
        }
    }

    public static class Judge {
        private Boolean enabled;
        private Boolean semanticEnabled;
        private Double temperature;
        private Integer maxAttempts;
        private Boolean failOpen;
        private Boolean blockOnInsufficientEvidence;
        /**
         * 新配置：有序路由列表，每个 endpoint 可配置一组候选 model。
         */
        private List<JudgeRoute> routes = new ArrayList<>();

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public Boolean getSemanticEnabled() {
            return semanticEnabled;
        }

        public void setSemanticEnabled(Boolean semanticEnabled) {
            this.semanticEnabled = semanticEnabled;
        }

        public Double getTemperature() {
            return temperature;
        }

        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }

        public Integer getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(Integer maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Boolean getFailOpen() {
            return failOpen;
        }

        public void setFailOpen(Boolean failOpen) {
            this.failOpen = failOpen;
        }

        public Boolean getBlockOnInsufficientEvidence() {
            return blockOnInsufficientEvidence;
        }

        public void setBlockOnInsufficientEvidence(Boolean blockOnInsufficientEvidence) {
            this.blockOnInsufficientEvidence = blockOnInsufficientEvidence;
        }

        public List<JudgeRoute> getRoutes() {
            return routes;
        }

        public void setRoutes(List<JudgeRoute> routes) {
            this.routes = routes == null ? new ArrayList<>() : routes;
        }
    }

    public static class JudgeRoute {
        private String endpointName;
        private List<String> models = new ArrayList<>();

        public String getEndpointName() {
            return endpointName;
        }

        public void setEndpointName(String endpointName) {
            this.endpointName = endpointName;
        }

        public List<String> getModels() {
            return models;
        }

        public void setModels(List<String> models) {
            this.models = models == null ? new ArrayList<>() : models;
        }
    }

    public static class MultiTurn {
        private Compression compression = new Compression();

        public Compression getCompression() {
            return compression;
        }

        public void setCompression(Compression compression) {
            this.compression = compression == null ? new Compression() : compression;
        }
    }

    public static class Compression {
        private Boolean enabled;
        private String strategy;
        private String summaryEndpoint;
        private String summaryModel;
        private List<String> summaryProviderOrder = new ArrayList<>();
        private Integer summaryMaxChars;
        private Double summaryTemperature;
        private Integer minMessagesForSummary;
        private Integer summaryMaxMessages;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public String getStrategy() {
            return strategy;
        }

        public void setStrategy(String strategy) {
            this.strategy = strategy;
        }

        public String getSummaryEndpoint() {
            return summaryEndpoint;
        }

        public void setSummaryEndpoint(String summaryEndpoint) {
            this.summaryEndpoint = summaryEndpoint;
        }

        public String getSummaryModel() {
            return summaryModel;
        }

        public void setSummaryModel(String summaryModel) {
            this.summaryModel = summaryModel;
        }

        public List<String> getSummaryProviderOrder() {
            return summaryProviderOrder;
        }

        public void setSummaryProviderOrder(List<String> summaryProviderOrder) {
            this.summaryProviderOrder = summaryProviderOrder == null ? new ArrayList<>() : summaryProviderOrder;
        }

        public Integer getSummaryMaxChars() {
            return summaryMaxChars;
        }

        public void setSummaryMaxChars(Integer summaryMaxChars) {
            this.summaryMaxChars = summaryMaxChars;
        }

        public Double getSummaryTemperature() {
            return summaryTemperature;
        }

        public void setSummaryTemperature(Double summaryTemperature) {
            this.summaryTemperature = summaryTemperature;
        }

        public Integer getMinMessagesForSummary() {
            return minMessagesForSummary;
        }

        public void setMinMessagesForSummary(Integer minMessagesForSummary) {
            this.minMessagesForSummary = minMessagesForSummary;
        }

        public Integer getSummaryMaxMessages() {
            return summaryMaxMessages;
        }

        public void setSummaryMaxMessages(Integer summaryMaxMessages) {
            this.summaryMaxMessages = summaryMaxMessages;
        }
    }

    public static class Observability {
        private OpenRouter openrouter = new OpenRouter();

        public OpenRouter getOpenrouter() {
            return openrouter;
        }

        public void setOpenrouter(OpenRouter openrouter) {
            this.openrouter = openrouter == null ? new OpenRouter() : openrouter;
        }
    }

    public static class OpenRouter {
        private CostEnrichment costEnrichment = new CostEnrichment();

        public CostEnrichment getCostEnrichment() {
            return costEnrichment;
        }

        public void setCostEnrichment(CostEnrichment costEnrichment) {
            this.costEnrichment = costEnrichment == null ? new CostEnrichment() : costEnrichment;
        }
    }

    public static class CostEnrichment {
        private Boolean enabled = false;
        private Integer timeoutMs = 5000;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public Integer getTimeoutMs() {
            return timeoutMs;
        }
        
        public void setTimeoutMs(Integer timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }

    public static class Prompts {
        private String agentRunSystemPrompt;
        private String todoPlannerSystemPromptTemplate;
        private String workflowFinalSystemPrompt;
        private String workflowTodoRecoverySystemPrompt;
        private String parallelPlannerSystemPromptTemplate;
        private String parallelFinalSystemPrompt;
        private String parallelPatchPlannerSystemPromptTemplate;
        private String planJudgeSystemPromptTemplate;
        private String semanticJudgeSystemPromptTemplate;
        private String subAgentPlannerSystemPromptTemplate;
        private String subAgentSummarySystemPrompt;
        private String pythonRefineSystemPrompt;
        private String pythonRefineRequirementsFile;
        private List<String> pythonRefineRequirements = new ArrayList<>();
        private String pythonRefineOutputInstruction;
        private List<DatasetFieldSpec> datasetFieldSpecs = new ArrayList<>();
        private String datasetFieldSpecsFile;
        private String orchestratorPlanningSystemPrompt;
        private String orchestratorSummarySystemPrompt;

        public String getAgentRunSystemPrompt() {
            return agentRunSystemPrompt;
        }

        public void setAgentRunSystemPrompt(String agentRunSystemPrompt) {
            this.agentRunSystemPrompt = agentRunSystemPrompt;
        }

        public String getTodoPlannerSystemPromptTemplate() {
            return todoPlannerSystemPromptTemplate;
        }

        public void setTodoPlannerSystemPromptTemplate(String todoPlannerSystemPromptTemplate) {
            this.todoPlannerSystemPromptTemplate = todoPlannerSystemPromptTemplate;
        }

        public String getWorkflowFinalSystemPrompt() {
            return workflowFinalSystemPrompt;
        }

        public void setWorkflowFinalSystemPrompt(String workflowFinalSystemPrompt) {
            this.workflowFinalSystemPrompt = workflowFinalSystemPrompt;
        }

        public String getWorkflowTodoRecoverySystemPrompt() {
            return workflowTodoRecoverySystemPrompt;
        }

        public void setWorkflowTodoRecoverySystemPrompt(String workflowTodoRecoverySystemPrompt) {
            this.workflowTodoRecoverySystemPrompt = workflowTodoRecoverySystemPrompt;
        }

        public String getParallelPlannerSystemPromptTemplate() {
            return parallelPlannerSystemPromptTemplate;
        }

        public void setParallelPlannerSystemPromptTemplate(String parallelPlannerSystemPromptTemplate) {
            this.parallelPlannerSystemPromptTemplate = parallelPlannerSystemPromptTemplate;
        }

        public String getParallelFinalSystemPrompt() {
            return parallelFinalSystemPrompt;
        }

        public void setParallelFinalSystemPrompt(String parallelFinalSystemPrompt) {
            this.parallelFinalSystemPrompt = parallelFinalSystemPrompt;
        }

        public String getParallelPatchPlannerSystemPromptTemplate() {
            return parallelPatchPlannerSystemPromptTemplate;
        }

        public void setParallelPatchPlannerSystemPromptTemplate(String parallelPatchPlannerSystemPromptTemplate) {
            this.parallelPatchPlannerSystemPromptTemplate = parallelPatchPlannerSystemPromptTemplate;
        }

        public String getPlanJudgeSystemPromptTemplate() {
            return planJudgeSystemPromptTemplate;
        }

        public void setPlanJudgeSystemPromptTemplate(String planJudgeSystemPromptTemplate) {
            this.planJudgeSystemPromptTemplate = planJudgeSystemPromptTemplate;
        }

        public String getSemanticJudgeSystemPromptTemplate() {
            return semanticJudgeSystemPromptTemplate;
        }

        public void setSemanticJudgeSystemPromptTemplate(String semanticJudgeSystemPromptTemplate) {
            this.semanticJudgeSystemPromptTemplate = semanticJudgeSystemPromptTemplate;
        }

        public String getSubAgentPlannerSystemPromptTemplate() {
            return subAgentPlannerSystemPromptTemplate;
        }

        public void setSubAgentPlannerSystemPromptTemplate(String subAgentPlannerSystemPromptTemplate) {
            this.subAgentPlannerSystemPromptTemplate = subAgentPlannerSystemPromptTemplate;
        }

        public String getSubAgentSummarySystemPrompt() {
            return subAgentSummarySystemPrompt;
        }

        public void setSubAgentSummarySystemPrompt(String subAgentSummarySystemPrompt) {
            this.subAgentSummarySystemPrompt = subAgentSummarySystemPrompt;
        }

        public String getPythonRefineSystemPrompt() {
            return pythonRefineSystemPrompt;
        }

        public void setPythonRefineSystemPrompt(String pythonRefineSystemPrompt) {
            this.pythonRefineSystemPrompt = pythonRefineSystemPrompt;
        }

        public String getPythonRefineRequirementsFile() {
            return pythonRefineRequirementsFile;
        }

        public void setPythonRefineRequirementsFile(String pythonRefineRequirementsFile) {
            this.pythonRefineRequirementsFile = pythonRefineRequirementsFile;
        }

        public List<String> getPythonRefineRequirements() {
            return pythonRefineRequirements;
        }

        public void setPythonRefineRequirements(List<String> pythonRefineRequirements) {
            this.pythonRefineRequirements = pythonRefineRequirements == null ? new ArrayList<>() : pythonRefineRequirements;
        }

        public String getPythonRefineOutputInstruction() {
            return pythonRefineOutputInstruction;
        }

        public void setPythonRefineOutputInstruction(String pythonRefineOutputInstruction) {
            this.pythonRefineOutputInstruction = pythonRefineOutputInstruction;
        }

        public List<DatasetFieldSpec> getDatasetFieldSpecs() {
            return datasetFieldSpecs;
        }

        public void setDatasetFieldSpecs(List<DatasetFieldSpec> datasetFieldSpecs) {
            this.datasetFieldSpecs = datasetFieldSpecs == null ? new ArrayList<>() : datasetFieldSpecs;
        }

        public String getDatasetFieldSpecsFile() {
            return datasetFieldSpecsFile;
        }

        public void setDatasetFieldSpecsFile(String datasetFieldSpecsFile) {
            this.datasetFieldSpecsFile = datasetFieldSpecsFile;
        }

        public String getOrchestratorPlanningSystemPrompt() {
            return orchestratorPlanningSystemPrompt;
        }

        public void setOrchestratorPlanningSystemPrompt(String orchestratorPlanningSystemPrompt) {
            this.orchestratorPlanningSystemPrompt = orchestratorPlanningSystemPrompt;
        }

        public String getOrchestratorSummarySystemPrompt() {
            return orchestratorSummarySystemPrompt;
        }

        public void setOrchestratorSummarySystemPrompt(String orchestratorSummarySystemPrompt) {
            this.orchestratorSummarySystemPrompt = orchestratorSummarySystemPrompt;
        }
    }

    public static class DatasetFieldSpec {
        private String name;
        private String meaning;
        private String dataType;
        private String dataFormat;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getMeaning() {
            return meaning;
        }

        public void setMeaning(String meaning) {
            this.meaning = meaning;
        }

        public String getDataType() {
            return dataType;
        }

        public void setDataType(String dataType) {
            this.dataType = dataType;
        }

        public String getDataFormat() {
            return dataFormat;
        }

        public void setDataFormat(String dataFormat) {
            this.dataFormat = dataFormat;
        }
    }
}
