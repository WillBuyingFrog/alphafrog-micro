package world.willfrog.alphafrogmicro.frontend.controller.domestic;

import com.google.protobuf.util.JsonFormat;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import world.willfrog.alphafrogmicro.domestic.idl.*;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexService;

@Slf4j
@Controller
@RequestMapping("/domestic/common")
public class DomesticCommonController {

    @DubboReference
    private DomesticIndexService domesticIndexService;

    @GetMapping("/trade-calendar/count")
    public ResponseEntity<String> getTradingDaysCountByDateRange(@RequestParam("start_date_timestamp") long startDateTimestamp,
                                                                 @RequestParam("end_date_timestamp") long endDateTimestamp,
                                                                 @RequestParam(value = "exchange", required = false, defaultValue = "SSE") String exchange) {
        try {
            DomesticTradingDaysCountResponse response = domesticIndexService.getTradingDaysCountByDateRange(
                    DomesticTradingDaysCountRequest.newBuilder()
                            .setExchange(exchange)
                            .setStartDate(startDateTimestamp)
                            .setEndDate(endDateTimestamp)
                            .build());

            String jsonResponse = JsonFormat.printer()
                    .preservingProtoFieldNames()
                    .omittingInsignificantWhitespace()
                    .includingDefaultValueFields()
                    .print(response);

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonResponse);
        } catch (Exception e) {
            log.error("Error occurred while counting trading days: {}", e.getMessage());
            return ResponseEntity.status(500).body("Error occurred while counting trading days");
        }
    }
}
