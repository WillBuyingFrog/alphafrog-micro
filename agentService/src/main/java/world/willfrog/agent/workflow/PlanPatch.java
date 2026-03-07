package world.willfrog.agent.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanPatch {
    private PatchType patchType;
    private String targetTodoId;
    @Builder.Default
    private Map<String, Object> patchData = new LinkedHashMap<>();
    private String reason;
}
