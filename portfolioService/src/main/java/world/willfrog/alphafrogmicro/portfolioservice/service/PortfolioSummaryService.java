package world.willfrog.alphafrogmicro.portfolioservice.service;

import world.willfrog.alphafrogmicro.common.pojo.portfolio.PortfolioDailySummaryDto;

import java.time.LocalDate;
import java.util.List;

public interface PortfolioSummaryService {

    /**
     * 生成指定投资组合在特定日期的日度表现摘要，并可与一系列历史日期进行比较。
     *
     * @param portfolioId 投资组合ID
     * @param currentDate 目标当前日期
     * @param comparisonDates 一系列用于比较的历史日期
     * @return 投资组合日度表现摘要DTO
     */
    PortfolioDailySummaryDto generateDailySummary(Long portfolioId, LocalDate currentDate, List<LocalDate> comparisonDates);

} 