package world.willfrog.agentlangchain.agenticpoc;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class LangchainAgenticPocResult {
    private boolean success;
    private String summary;
    private Map<String, String> branchOutputs;
    private String limitationNote;
}
