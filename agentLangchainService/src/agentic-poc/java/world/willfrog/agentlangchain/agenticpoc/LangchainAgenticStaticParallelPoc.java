package world.willfrog.agentlangchain.agenticpoc;

import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.model.chat.ChatModel;
import world.willfrog.agentlangchain.agenticpoc.branch.Csi500ReadOnlyBranchAgent;
import world.willfrog.agentlangchain.agenticpoc.branch.Hs300ReadOnlyBranchAgent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Experimental POC: {@code parallelBuilder} for two static read-only branches.
 * Not wired to Dubbo / run pipeline; requires {@code -Pagentic-poc} at compile time.
 */
public final class LangchainAgenticStaticParallelPoc {

    private static final String LIMITATION = """
            Covers: static fan-out of two independent LLM branches via langchain4j-agentic parallelBuilder.
            Does NOT cover: runtime dependsOn DAG, ToolRouter governance, budget/obs parity, or production routing.
            """;

    private LangchainAgenticStaticParallelPoc() {
    }

    public static LangchainAgenticPocResult run(ChatModel chatModel, String goal) {
        if (chatModel == null) {
            return LangchainAgenticPocResult.builder()
                    .success(false)
                    .summary("chat_model_required")
                    .limitationNote(LIMITATION)
                    .build();
        }
        try {
            Hs300ReadOnlyBranchAgent hs300 = AgenticServices.agentBuilder(Hs300ReadOnlyBranchAgent.class)
                    .chatModel(chatModel)
                    .outputKey("hs300")
                    .build();
            Csi500ReadOnlyBranchAgent csi500 = AgenticServices.agentBuilder(Csi500ReadOnlyBranchAgent.class)
                    .chatModel(chatModel)
                    .outputKey("csi500")
                    .build();

            UntypedAgent parallel = AgenticServices.parallelBuilder()
                    .subAgents(hs300, csi500)
                    .executor(Executors.newFixedThreadPool(2))
                    .outputKey("merged")
                    .output(scope -> {
                        Map<String, String> merged = new LinkedHashMap<>();
                        merged.put("hs300", String.valueOf(scope.readState("hs300", "")));
                        merged.put("csi500", String.valueOf(scope.readState("csi500", "")));
                        return merged;
                    })
                    .build();

            @SuppressWarnings("unchecked")
            Map<String, String> outputs = (Map<String, String>) parallel.invoke(Map.of("goal", goal == null ? "" : goal));
            boolean ok = outputs != null
                    && outputs.containsKey("hs300")
                    && outputs.containsKey("csi500");
            return LangchainAgenticPocResult.builder()
                    .success(ok)
                    .summary(ok ? "parallel_poc_ok" : "parallel_poc_incomplete")
                    .branchOutputs(outputs == null ? Map.of() : outputs)
                    .limitationNote(LIMITATION)
                    .build();
        } catch (Exception e) {
            return LangchainAgenticPocResult.builder()
                    .success(false)
                    .summary(e.getMessage())
                    .limitationNote(LIMITATION)
                    .build();
        }
    }
}
