package world.willfrog.alphafrogmicro.frontend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class AgentLegacyDeprecationFilter extends OncePerRequestFilter {

    private static final String LEGACY_PREFIX = "/api/agent-legacy";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (request.getRequestURI() != null && request.getRequestURI().startsWith(LEGACY_PREFIX)) {
            response.setHeader("Deprecation", "true");
            response.setHeader("X-Deprecated-Endpoint", LEGACY_PREFIX);
            response.setHeader("Link", "</api/agent>; rel=\"successor-version\"");
        }
        filterChain.doFilter(request, response);
    }
}
