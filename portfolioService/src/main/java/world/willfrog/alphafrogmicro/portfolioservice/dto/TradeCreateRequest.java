package world.willfrog.alphafrogmicro.portfolioservice.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class TradeCreateRequest {
    @Valid
    @NotEmpty
    private List<TradeCreateItem> items;
}
