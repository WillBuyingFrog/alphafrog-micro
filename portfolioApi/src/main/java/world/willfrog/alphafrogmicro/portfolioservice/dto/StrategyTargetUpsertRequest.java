package world.willfrog.alphafrogmicro.portfolioservice.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public class StrategyTargetUpsertRequest {
    @Valid
    @NotEmpty
    private List<StrategyTargetUpsertItem> items;

    public List<StrategyTargetUpsertItem> getItems() {
        return items;
    }

    public void setItems(List<StrategyTargetUpsertItem> items) {
        this.items = items;
    }
}
