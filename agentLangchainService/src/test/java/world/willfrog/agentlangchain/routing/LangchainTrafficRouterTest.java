package world.willfrog.agentlangchain.routing;

import org.junit.jupiter.api.Test;
import world.willfrog.agentlangchain.config.LangchainServiceProperties;

import static org.junit.jupiter.api.Assertions.*;

class LangchainTrafficRouterTest {

    @Test
    void zeroPercentNeverRoutes() {
        LangchainServiceProperties properties = new LangchainServiceProperties();
        properties.getTraffic().setCanaryPercent(0);
        LangchainTrafficRouter router = new LangchainTrafficRouter(properties);
        assertFalse(router.shouldRouteToLangchain("user-1"));
    }

    @Test
    void hundredPercentAlwaysRoutes() {
        LangchainServiceProperties properties = new LangchainServiceProperties();
        properties.getTraffic().setCanaryPercent(100);
        LangchainTrafficRouter router = new LangchainTrafficRouter(properties);
        assertTrue(router.shouldRouteToLangchain("user-1"));
    }

    @Test
    void routingIsStableForSameKey() {
        LangchainServiceProperties properties = new LangchainServiceProperties();
        properties.getTraffic().setCanaryPercent(50);
        LangchainTrafficRouter router = new LangchainTrafficRouter(properties);
        boolean first = router.shouldRouteToLangchain("idempotency-key-abc");
        boolean second = router.shouldRouteToLangchain("idempotency-key-abc");
        assertEquals(first, second);
    }
}
