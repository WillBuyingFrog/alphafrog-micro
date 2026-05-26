package world.willfrog.agentlangchain.agenticpoc;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LangchainAgenticStaticParallelPocTest {

    @Test
    void run_shouldExecuteBothParallelBranches() {
        LangchainAgenticPocResult result = LangchainAgenticStaticParallelPoc.run(new BranchAwareChatModel(), "compare indices");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getBranchOutputs()).containsEntry("hs300", "HS300_OK");
        assertThat(result.getBranchOutputs()).containsEntry("csi500", "CSI500_OK");
        assertThat(result.getLimitationNote()).contains("Does NOT cover");
    }

    /** Returns branch-specific text based on prompt content (safe under parallel invocation). */
    private static final class BranchAwareChatModel implements ChatModel {
        @Override
        public ChatResponse chat(ChatRequest request) {
            String prompt = request == null || request.messages() == null
                    ? ""
                    : request.messages().toString().toLowerCase();
            String text;
            if (prompt.contains("hs300")) {
                text = "HS300_OK";
            } else if (prompt.contains("csi500")) {
                text = "CSI500_OK";
            } else {
                text = "UNKNOWN";
            }
            return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
        }
    }
}
