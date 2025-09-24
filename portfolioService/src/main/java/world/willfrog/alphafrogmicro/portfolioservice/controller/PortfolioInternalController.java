package world.willfrog.alphafrogmicro.portfolioservice.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import world.willfrog.alphafrogmicro.common.pojo.portfolio.PortfolioDailySummaryDto;
import world.willfrog.alphafrogmicro.portfolioservice.service.PortfolioSummaryService;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/internal/portfolio") // 路径与 FeignClient 中的 path 一致
public class PortfolioInternalController {

    private static final Logger logger = LoggerFactory.getLogger(PortfolioInternalController.class);

    private final PortfolioSummaryService portfolioSummaryService;

    @Autowired
    public PortfolioInternalController(PortfolioSummaryService portfolioSummaryService) {
        this.portfolioSummaryService = portfolioSummaryService;
    }

    @PostMapping("/summary")
    public ResponseEntity<PortfolioDailySummaryDto> generateDailySummaryInternal(
            @RequestParam("portfolioId") Long portfolioId,
            @RequestParam("currentDate") String currentDateString,
            @RequestParam(value = "comparisonDates", required = false) List<String> comparisonDateStrings) {
        
        LocalDate currentDate;
        try {
            currentDate = LocalDate.parse(currentDateString);
        } catch (DateTimeParseException e) {
            logger.warn("Invalid current date string in internal request: {}", currentDateString);
            return ResponseEntity.badRequest().build(); // 或者返回更详细的错误信息
        }

        List<LocalDate> comparisonDates = null;
        if (comparisonDateStrings != null && !comparisonDateStrings.isEmpty()) {
            try {
                comparisonDates = comparisonDateStrings.stream()
                                                     .map(LocalDate::parse)
                                                     .collect(Collectors.toList());
            } catch (DateTimeParseException e) {
                logger.warn("Invalid comparison date string in internal request: {}", e.getMessage());
                return ResponseEntity.badRequest().build();
            }
        }

        try {
            PortfolioDailySummaryDto summaryDto = portfolioSummaryService.generateDailySummary(
                portfolioId, 
                currentDate, 
                comparisonDates
            );
            
            if (summaryDto == null) {
                // portfolioService.generateDailySummary 返回 null 通常意味着 portfolio 未找到或无持仓等
                // Feign 调用方会根据此 null 进行处理
                return ResponseEntity.status(HttpStatus.OK).body(null); // 或者 NOT_FOUND 如果确定是未找到
            }
            return ResponseEntity.ok(summaryDto);
        } catch (Exception e) {
            logger.error("Error in internal portfolio summary generation for portfolioId: {}", portfolioId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
} 