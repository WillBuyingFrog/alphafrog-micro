package world.willfrog.agentlangchain.routing;

import org.springframework.stereotype.Component;
import world.willfrog.agentlangchain.config.LangchainServiceProperties;

/**
 * Consistent-hash canary router for dual Dubbo providers (legacy vs langchain).
 *
 * <p>Callers should pick provider <em>before</em> {@code CreateRun} using a stable key
 * (idempotency key preferred, else user id).</p>
 */
@Component
public class LangchainTrafficRouter {

    private final LangchainServiceProperties properties;

    public LangchainTrafficRouter(LangchainServiceProperties properties) {
        this.properties = properties;
    }

    public boolean shouldRouteToLangchain(String stableKey) {
        if (stableKey == null || stableKey.isBlank()) {
            return false;
        }
        int percent = Math.max(0, Math.min(100, properties.getTraffic().getCanaryPercent()));
        if (percent <= 0) {
            return false;
        }
        if (percent >= 100) {
            return true;
        }
        int bucket = Math.floorMod(stableKey.hashCode(), 100);
        return bucket < percent;
    }

    public boolean shouldRouteToLangchainForRun(String runId) {
        return shouldRouteToLangchain(runId);
    }
}
