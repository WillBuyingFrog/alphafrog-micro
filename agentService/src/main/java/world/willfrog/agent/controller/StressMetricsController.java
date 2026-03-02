package world.willfrog.agent.controller;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import world.willfrog.agent.config.StressTestProperties;
import world.willfrog.agent.model.StressConfigRequest;
import world.willfrog.agent.model.StressMetricsResponse;

import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/actuator/stress-metrics")
@RequiredArgsConstructor
public class StressMetricsController {

    private final MeterRegistry meterRegistry;
    private final StressTestProperties stressTestProperties;

    @GetMapping
    public StressMetricsResponse getStressMetrics() {
        double hitRate = calculateCacheHitRate();

        double latencyP50 = getTimerPercentile("cache.lookup", 0.5);
        double latencyP95 = getTimerPercentile("cache.lookup", 0.95);
        double latencyP99 = getTimerPercentile("cache.lookup", 0.99);

        int activeRuns = getActiveRuns();

        return StressMetricsResponse.builder()
                .cacheHitRate(hitRate)
                .latencyP50(latencyP50)
                .latencyP95(latencyP95)
                .latencyP99(latencyP99)
                .activeRuns(activeRuns)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    @PostMapping("/config")
    public ResponseEntity<Void> updateStressConfig(@RequestBody StressConfigRequest request) {
        if (!stressTestProperties.isEnabled()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        if (request.getToolLatencyEnabled() != null) {
            stressTestProperties.setToolLatencyEnabled(request.getToolLatencyEnabled());
        }
        if (request.getToolLatencyMs() != null) {
            stressTestProperties.setToolLatencyMs(request.getToolLatencyMs());
        }
        if (request.getToolFailureRate() != null) {
            stressTestProperties.setToolFailureRate(request.getToolFailureRate());
        }
        return ResponseEntity.ok().build();
    }

    private double calculateCacheHitRate() {
        Counter hitCounter = meterRegistry.find("cache.hit").counter();
        Counter missCounter = meterRegistry.find("cache.miss").counter();

        double hits = hitCounter != null ? hitCounter.count() : 0;
        double misses = missCounter != null ? missCounter.count() : 0;
        double total = hits + misses;

        return total > 0 ? hits / total : 0.0;
    }

    private double getTimerPercentile(String timerName, double percentile) {
        Timer timer = meterRegistry.find(timerName).timer();
        if (timer == null) {
            return 0.0;
        }
        var snapshot = timer.takeSnapshot();
        for (ValueAtPercentile vp : snapshot.percentileValues()) {
            if (Math.abs(vp.percentile() - percentile) < 0.001) {
                return vp.value(TimeUnit.MILLISECONDS);
            }
        }
        return 0.0;
    }

    private int getActiveRuns() {
        try {
            var gauge = meterRegistry.find("run.active").gauge();
            return gauge != null ? (int) gauge.value() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
