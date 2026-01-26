package world.willfrog.alphafrogmicro.portfolioservice.domain;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class PricePoint {
    private Long tradeDate;
    private BigDecimal close;
}
