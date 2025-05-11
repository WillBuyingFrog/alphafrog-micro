package world.willfrog.alphafrogmicro.portfolioservice.service;

import world.willfrog.alphafrogmicro.portfolioservice.dto.PortfolioDailySummaryDto;

import java.time.LocalDate;

public interface PortfolioSummaryService {

    /**
     * 生成指定投资组合在特定日期的日度表现摘要。
     *
     * @param portfolioId 投资组合ID
     * @param date 目标日期
     * @return 投资组合日度表现摘要DTO，如果投资组合未找到或无法计算，则可能返回Optional.empty()或抛出异常
     */
    PortfolioDailySummaryDto generateDailySummary(Long portfolioId, LocalDate date);

} 