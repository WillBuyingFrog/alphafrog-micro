package world.willfrog.alphafrogmicro.frontend.service;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimitingService {

    private final RateLimiterRegistry rateLimiterRegistry;

    public boolean tryAcquire(String rateLimiterName) {
        try {
            RateLimiter rateLimiter = rateLimiterRegistry.rateLimiter(rateLimiterName);
            return rateLimiter.acquirePermission();
        } catch (RequestNotPermitted e) {
            log.warn("Rate limit exceeded for: {}", rateLimiterName);
            return false;
        }
    }

    public <T> T executeWithRateLimit(String rateLimiterName, Supplier<T> supplier) {
        RateLimiter rateLimiter = rateLimiterRegistry.rateLimiter(rateLimiterName);
        try {
            return RateLimiter.decorateSupplier(rateLimiter, supplier).get();
        } catch (RequestNotPermitted e) {
            log.warn("Rate limit exceeded for: {}", rateLimiterName);
            throw new RuntimeException("Too many requests, please try again later");
        }
    }
}