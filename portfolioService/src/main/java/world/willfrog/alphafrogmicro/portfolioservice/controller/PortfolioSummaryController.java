package world.willfrog.alphafrogmicro.portfolioservice.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import world.willfrog.alphafrogmicro.portfolioservice.dto.PortfolioDailySummaryDto;
import world.willfrog.alphafrogmicro.portfolioservice.service.PortfolioSummaryService;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/portfolio")
public class PortfolioSummaryController {

    private final PortfolioSummaryService portfolioSummaryService;

    @Autowired
    public PortfolioSummaryController(PortfolioSummaryService portfolioSummaryService) {
        this.portfolioSummaryService = portfolioSummaryService;
    }

    @GetMapping("/{portfolioId}/summary/daily")
    public ResponseEntity<PortfolioDailySummaryDto> getDailyPortfolioSummary(
            @PathVariable Long portfolioId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        if (portfolioId == null || date == null) {
            return ResponseEntity.badRequest().build(); // Or a more descriptive error
        }

        try {
            PortfolioDailySummaryDto summaryDto = portfolioSummaryService.generateDailySummary(portfolioId, date);
            if (summaryDto == null) {
                // This could happen if portfolio is not found, handled by service returning null
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(summaryDto);
        } catch (Exception e) {
            // Log the exception
            // Consider specific exception handling for different scenarios (e.g., data access issues, calculation errors)
            // For now, a generic server error
            // logger.error("Error generating daily summary for portfolio {} on date {}: {}", portfolioId, date, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    // TODO: Add other endpoints for managing portfolios and holdings if this controller should handle them,
    // or create a separate PortfolioManagementController for CRUD operations on portfolios/holdings.
    // For instance:
    // POST /api/portfolio
    // GET /api/portfolio/{portfolioId}
    // POST /api/portfolio/{portfolioId}/holdings
    // etc.
} 