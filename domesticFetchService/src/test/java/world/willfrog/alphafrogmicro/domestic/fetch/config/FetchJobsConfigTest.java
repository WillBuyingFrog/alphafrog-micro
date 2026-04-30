package world.willfrog.alphafrogmicro.domestic.fetch.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FetchJobsConfigTest {

    @Test
    void refreshScheduleShouldDefaultToTenSeconds() throws Exception {
        Scheduled scheduled = FetchJobsConfig.class
                .getDeclaredMethod("refresh")
                .getAnnotation(Scheduled.class);

        assertEquals("${af.fetch.jobs.config-refresh-interval-ms:10000}", scheduled.fixedDelayString());
    }
}
