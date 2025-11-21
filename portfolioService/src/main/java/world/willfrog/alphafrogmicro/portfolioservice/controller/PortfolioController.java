package world.willfrog.alphafrogmicro.portfolioservice.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import world.willfrog.alphafrogmicro.common.dto.ResponseCode;
import world.willfrog.alphafrogmicro.common.dto.ResponseWrapper;
import world.willfrog.alphafrogmicro.portfolioservice.dto.PageResult;
import world.willfrog.alphafrogmicro.portfolioservice.dto.PortfolioCreateRequest;
import world.willfrog.alphafrogmicro.portfolioservice.dto.PortfolioResponse;
import world.willfrog.alphafrogmicro.portfolioservice.service.PortfolioService;

@RestController
@RequestMapping("/api/portfolios")
public class PortfolioController {

    private final PortfolioService portfolioService;

    public PortfolioController(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    @PostMapping
    public ResponseWrapper<PortfolioResponse> create(
            @RequestHeader("X-User-Id") String userId,
            @Valid @RequestBody PortfolioCreateRequest request) {
        return ResponseWrapper.success(portfolioService.create(userId, request));
    }

    @GetMapping
    public ResponseWrapper<PageResult<PortfolioResponse>> list(
            @RequestHeader("X-User-Id") String userId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResponseWrapper.success(portfolioService.list(userId, status, keyword, page, size));
    }

    @GetMapping("/{id}")
    public ResponseWrapper<PortfolioResponse> getById(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable("id") Long id) {
        return ResponseWrapper.success(portfolioService.getById(id, userId));
    }

    @GetMapping("/health")
    public ResponseWrapper<String> health() {
        return ResponseWrapper.success("ok", ResponseCode.SUCCESS.getMessage());
    }
}
