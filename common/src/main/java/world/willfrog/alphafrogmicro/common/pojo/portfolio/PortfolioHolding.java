package world.willfrog.alphafrogmicro.common.pojo.portfolio;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "portfolio_holding")
public class PortfolioHolding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asset_identifier", nullable = false)
    private String assetIdentifier; // tsCode for stock or fund

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", nullable = false)
    private AssetType assetType;

    @Column(nullable = false)
    private BigDecimal quantity;

    @Column(name = "average_cost_price", nullable = false)
    private BigDecimal averageCostPrice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "portfolio_id", nullable = false)
    private Portfolio portfolio;

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAssetIdentifier() {
        return assetIdentifier;
    }

    public void setAssetIdentifier(String assetIdentifier) {
        this.assetIdentifier = assetIdentifier;
    }

    public AssetType getAssetType() {
        return assetType;
    }

    public void setAssetType(AssetType assetType) {
        this.assetType = assetType;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getAverageCostPrice() {
        return averageCostPrice;
    }

    public void setAverageCostPrice(BigDecimal averageCostPrice) {
        this.averageCostPrice = averageCostPrice;
    }

    public Portfolio getPortfolio() {
        return portfolio;
    }

    public void setPortfolio(Portfolio portfolio) {
        this.portfolio = portfolio;
    }
} 