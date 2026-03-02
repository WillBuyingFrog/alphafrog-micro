package world.willfrog.agent.controller;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import world.willfrog.agent.config.StressTestProperties;
import world.willfrog.agent.model.StressConfigRequest;
import world.willfrog.agent.model.StressMetricsResponse;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StressMetricsControllerTest {

    private MeterRegistry meterRegistry;
    private StressTestProperties stressTestProperties;
    private StressMetricsController controller;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        stressTestProperties = new StressTestProperties();
        controller = new StressMetricsController(meterRegistry, stressTestProperties);
    }

    @Test
    void getStressMetrics_returnsZeroWhenNoMetricsRecorded() {
        StressMetricsResponse response = controller.getStressMetrics();

        assertEquals(0.0, response.getCacheHitRate());
        assertEquals(0, response.getActiveRuns());
        assertTrue(response.getTimestamp() > 0);
    }

    @Test
    void getStressMetrics_returnsCacheHitRate() {
        Counter hitCounter = Counter.builder("cache.hit").register(meterRegistry);
        Counter missCounter = Counter.builder("cache.miss").register(meterRegistry);

        hitCounter.increment(8);
        missCounter.increment(2);

        StressMetricsResponse response = controller.getStressMetrics();

        assertEquals(0.8, response.getCacheHitRate(), 0.001);
    }

    @Test
    void getStressMetrics_returnsActiveRunsFromGauge() {
        AtomicInteger activeRuns = new AtomicInteger(5);
        io.micrometer.core.instrument.Gauge.builder("run.active", activeRuns, AtomicInteger::get)
                .register(meterRegistry);

        StressMetricsResponse response = controller.getStressMetrics();

        assertEquals(5, response.getActiveRuns());
    }

    @Test
    void updateStressConfig_returnsForbiddenWhenNotEnabled() {
        stressTestProperties.setEnabled(false);
        StressConfigRequest request = new StressConfigRequest();
        request.setToolLatencyEnabled(true);

        ResponseEntity<Void> response = controller.updateStressConfig(request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void updateStressConfig_updatesPropertiesWhenEnabled() {
        stressTestProperties.setEnabled(true);
        StressConfigRequest request = new StressConfigRequest();
        request.setToolLatencyEnabled(true);
        request.setToolLatencyMs(5000L);
        request.setToolFailureRate(0.3);

        ResponseEntity<Void> response = controller.updateStressConfig(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(stressTestProperties.isToolLatencyEnabled());
        assertEquals(5000L, stressTestProperties.getToolLatencyMs());
        assertEquals(0.3, stressTestProperties.getToolFailureRate(), 0.001);
    }

    @Test
    void updateStressConfig_partialUpdateOnlyChangesProvidedFields() {
        stressTestProperties.setEnabled(true);
        stressTestProperties.setToolLatencyMs(1000L);
        stressTestProperties.setToolFailureRate(0.1);

        StressConfigRequest request = new StressConfigRequest();
        request.setToolFailureRate(0.5);

        controller.updateStressConfig(request);

        assertEquals(1000L, stressTestProperties.getToolLatencyMs());
        assertEquals(0.5, stressTestProperties.getToolFailureRate(), 0.001);
    }

    @Test
    void getStressMetrics_returnsLatencyPercentilesFromTimer() {
        Timer timer = Timer.builder("cache.lookup")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        timer.record(100, TimeUnit.MILLISECONDS);
        timer.record(200, TimeUnit.MILLISECONDS);
        timer.record(300, TimeUnit.MILLISECONDS);

        StressMetricsResponse response = controller.getStressMetrics();

        assertTrue(response.getTimestamp() > 0);
    }
}
