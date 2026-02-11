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
    private Runtime runtime = new Runtime();
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

    public static class Endpoint {
        private String baseUrl;
        private String apiKey;

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
    }

    public static class Runtime {
        private Resume resume = new Resume();
        private Cache cache = new Cache();
        private Planning planning = new Planning();
        private Judge judge = new Judge();

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

        public Planning getPlanning() {
            return planning;
        }

        public void setPlanning(Planning planning) {
            this.planning = planning == null ? new Planning() : planning;
        }

        public Judge getJudge() {
            return judge;
        }

        public void setJudge(Judge judge) {
            this.judge = judge == null ? new Judge() : judge;
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
        private Double complexityPenaltyLambda;

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

        public Double getComplexityPenaltyLambda() {
            return complexityPenaltyLambda;
        }

        public void setComplexityPenaltyLambda(Double complexityPenaltyLambda) {
            this.complexityPenaltyLambda = complexityPenaltyLambda;
        }
    }

    public static class Judge {
        private Boolean enabled;
        private Double temperature;
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

        public Double getTemperature() {
            return temperature;
        }

        public void setTemperature(Double temperature) {
            this.temperature = temperature;
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

    public static class Prompts {
        private String agentRunSystemPrompt;
        private String parallelPlannerSystemPromptTemplate;
        private String parallelFinalSystemPrompt;
        private String parallelPatchPlannerSystemPromptTemplate;
        private String planJudgeSystemPromptTemplate;
        private String subAgentPlannerSystemPromptTemplate;
        private String subAgentSummarySystemPrompt;
        private String pythonRefineSystemPrompt;
        private List<String> pythonRefineRequirements = new ArrayList<>();
        private String pythonRefineOutputInstruction;
        private List<DatasetFieldSpec> datasetFieldSpecs = new ArrayList<>();
        private String orchestratorPlanningSystemPrompt;
        private String orchestratorSummarySystemPrompt;

        public String getAgentRunSystemPrompt() {
            return agentRunSystemPrompt;
        }

        public void setAgentRunSystemPrompt(String agentRunSystemPrompt) {
            this.agentRunSystemPrompt = agentRunSystemPrompt;
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
