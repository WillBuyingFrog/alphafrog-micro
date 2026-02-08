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

    public static class Prompts {
        private String agentRunSystemPrompt;
        private String parallelPlannerSystemPromptTemplate;
        private String parallelFinalSystemPrompt;
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
