package world.willfrog.alphafrogmicro.portfolioservice.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import world.willfrog.alphafrogmicro.common.dto.ResponseCode;
import world.willfrog.alphafrogmicro.portfolioservice.constants.PortfolioConstants;
import world.willfrog.alphafrogmicro.portfolioservice.domain.PortfolioPo;
import world.willfrog.alphafrogmicro.portfolioservice.dto.PageResult;
import world.willfrog.alphafrogmicro.portfolioservice.dto.PortfolioCreateRequest;
import world.willfrog.alphafrogmicro.portfolioservice.dto.PortfolioResponse;
import world.willfrog.alphafrogmicro.portfolioservice.dto.PortfolioUpdateRequest;
import world.willfrog.alphafrogmicro.portfolioservice.exception.BizException;
import world.willfrog.alphafrogmicro.portfolioservice.mapper.PortfolioMapper;
import world.willfrog.alphafrogmicro.portfolioservice.service.PortfolioService;
import world.willfrog.alphafrogmicro.portfolioservice.util.PortfolioConverter;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class PortfolioServiceImpl implements PortfolioService {

    private static final List<String> VISIBILITY_SET = List.of("private", "shared");
    private static final List<String> STATUS_SET = List.of("active", "archived");
    private static final List<String> PORTFOLIO_TYPE_SET = List.of("REAL", "STRATEGY", "MODEL");

    private final PortfolioMapper portfolioMapper;
    private final ObjectMapper objectMapper;

    public PortfolioServiceImpl(PortfolioMapper portfolioMapper, ObjectMapper objectMapper) {
        this.portfolioMapper = portfolioMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public PortfolioResponse create(String userId, PortfolioCreateRequest request) {
        String visibility = defaultVisibility(request.getVisibility());
        String timezone = defaultTimezone(request.getTimezone());
        String portfolioType = defaultPortfolioType(request.getPortfolioType());
        String baseCurrency = defaultBaseCurrency(request.getBaseCurrency());

        if (portfolioMapper.countActiveName(userId, request.getName()) > 0) {
            throw new BizException(ResponseCode.DATA_EXIST, "同名组合已存在");
        }

        PortfolioPo po = new PortfolioPo();
        po.setUserId(userId);
        po.setName(request.getName());
        po.setVisibility(visibility);
        po.setPortfolioType(portfolioType);
        po.setBaseCurrency(baseCurrency);
        po.setBenchmarkSymbol(request.getBenchmarkSymbol());
        po.setStatus(PortfolioConstants.DEFAULT_STATUS);
        po.setTimezone(timezone);
        po.setTagsJson(PortfolioConverter.toTagsJson(request.getTags(), objectMapper));
        po.setExtJson("{}");

        portfolioMapper.insert(po);
        po.setCreatedAt(OffsetDateTime.now());
        po.setUpdatedAt(po.getCreatedAt());
        return PortfolioConverter.toResponse(po, objectMapper);
    }

    @Override
    public PageResult<PortfolioResponse> list(String userId, String status, String keyword, int page, int size) {
        int pageNum = Math.max(page, PortfolioConstants.DEFAULT_PAGE);
        int pageSize = Math.min(Math.max(size, 1), PortfolioConstants.MAX_PAGE_SIZE);
        String normalizedStatus = normalizeStatus(status);

        int offset = (pageNum - 1) * pageSize;
        List<PortfolioPo> items = portfolioMapper.list(userId, normalizedStatus, keyword, offset, pageSize);
        long total = portfolioMapper.count(userId, normalizedStatus, keyword);

        List<PortfolioResponse> dtoList = items.stream()
                .map(po -> PortfolioConverter.toResponse(po, objectMapper))
                .toList();

        return PageResult.<PortfolioResponse>builder()
                .items(dtoList)
                .total(total)
                .page(pageNum)
                .size(pageSize)
                .build();
    }

    @Override
    public PortfolioResponse getById(Long id, String userId) {
        PortfolioPo po = portfolioMapper.findByIdAndUser(id, userId);
        if (po == null) {
            throw new BizException(ResponseCode.DATA_NOT_FOUND, "组合不存在");
        }
        return PortfolioConverter.toResponse(po, objectMapper);
    }

    private String defaultVisibility(String visibility) {
        String value = StringUtils.defaultIfBlank(visibility, PortfolioConstants.DEFAULT_VISIBILITY);
        if (!VISIBILITY_SET.contains(value)) {
            throw new BizException(ResponseCode.PARAM_ERROR, "visibility 仅支持 private/shared");
        }
        return value;
    }

    private String defaultTimezone(String timezone) {
        return StringUtils.defaultIfBlank(timezone, PortfolioConstants.DEFAULT_TIMEZONE);
    }

    private String defaultPortfolioType(String portfolioType) {
        String value = StringUtils.defaultIfBlank(portfolioType, PortfolioConstants.DEFAULT_PORTFOLIO_TYPE);
        String normalized = value.toUpperCase();
        if (!PORTFOLIO_TYPE_SET.contains(normalized)) {
            throw new BizException(ResponseCode.PARAM_ERROR, "portfolioType 仅支持 REAL/STRATEGY/MODEL");
        }
        return normalized;
    }

    private String defaultBaseCurrency(String baseCurrency) {
        String value = StringUtils.defaultIfBlank(baseCurrency, PortfolioConstants.DEFAULT_BASE_CURRENCY);
        return value.toUpperCase();
    }

    private String normalizeStatus(String status) {
        if (StringUtils.isBlank(status)) {
            return null;
        }
        if (!STATUS_SET.contains(status)) {
            throw new BizException(ResponseCode.PARAM_ERROR, "status 仅支持 active/archived");
        }
        return status;
    }

    @Override
    @Transactional
    public PortfolioResponse update(Long id, String userId, PortfolioUpdateRequest request) {
        PortfolioPo po = portfolioMapper.findByIdAndUser(id, userId);
        if (po == null) {
            throw new BizException(ResponseCode.DATA_NOT_FOUND, "组合不存在");
        }
        if (StringUtils.isNotBlank(request.getName())
                && !StringUtils.equals(request.getName(), po.getName())
                && portfolioMapper.countActiveName(userId, request.getName()) > 0) {
            throw new BizException(ResponseCode.DATA_EXIST, "同名组合已存在");
        }
        if (StringUtils.isNotBlank(request.getVisibility())) {
            po.setVisibility(defaultVisibility(request.getVisibility()));
        }
        if (request.getTags() != null) {
            po.setTagsJson(PortfolioConverter.toTagsJson(request.getTags(), objectMapper));
        }
        if (StringUtils.isNotBlank(request.getPortfolioType())) {
            po.setPortfolioType(defaultPortfolioType(request.getPortfolioType()));
        }
        if (StringUtils.isNotBlank(request.getBaseCurrency())) {
            po.setBaseCurrency(defaultBaseCurrency(request.getBaseCurrency()));
        }
        if (StringUtils.isNotBlank(request.getBenchmarkSymbol())) {
            po.setBenchmarkSymbol(request.getBenchmarkSymbol());
        }
        if (StringUtils.isNotBlank(request.getStatus())) {
            po.setStatus(normalizeStatus(request.getStatus()));
        }
        if (StringUtils.isNotBlank(request.getName())) {
            po.setName(request.getName());
        }

        portfolioMapper.update(po);
        return PortfolioConverter.toResponse(po, objectMapper);
    }

    @Override
    @Transactional
    public void archive(Long id, String userId) {
        PortfolioPo po = portfolioMapper.findByIdAndUser(id, userId);
        if (po == null) {
            throw new BizException(ResponseCode.DATA_NOT_FOUND, "组合不存在");
        }
        po.setStatus("archived");
        portfolioMapper.update(po);
    }
}
