package world.willfrog.alphafrogmicro.portfolioservice.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.experimental.UtilityClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import world.willfrog.alphafrogmicro.portfolioservice.domain.PortfolioPo;
import world.willfrog.alphafrogmicro.portfolioservice.dto.PortfolioResponse;

import java.util.Collections;
import java.util.List;

@UtilityClass
public class PortfolioConverter {
    private static final Logger log = LoggerFactory.getLogger(PortfolioConverter.class);
    private static final TypeReference<List<String>> LIST_STRING = new TypeReference<>() {};

    public static PortfolioResponse toResponse(PortfolioPo po, ObjectMapper objectMapper) {
        List<String> tags = parseTags(po.getTagsJson(), objectMapper);
        return PortfolioResponse.builder()
                .id(po.getId())
                .userId(po.getUserId())
                .name(po.getName())
                .visibility(po.getVisibility())
                .tags(tags)
                .portfolioType(po.getPortfolioType())
                .baseCurrency(po.getBaseCurrency())
                .benchmarkSymbol(po.getBenchmarkSymbol())
                .status(po.getStatus())
                .timezone(po.getTimezone())
                .createdAt(po.getCreatedAt())
                .updatedAt(po.getUpdatedAt())
                .build();
    }

    public static String toTagsJson(List<String> tags, ObjectMapper objectMapper) {
        try {
            if (tags == null) {
                return "[]";
            }
            return objectMapper.writeValueAsString(tags);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize tags, fallback to empty array", e);
            return "[]";
        }
    }

    private static List<String> parseTags(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, LIST_STRING);
        } catch (Exception e) {
            log.warn("Failed to parse tags json: {}", json, e);
            return Collections.emptyList();
        }
    }
}
