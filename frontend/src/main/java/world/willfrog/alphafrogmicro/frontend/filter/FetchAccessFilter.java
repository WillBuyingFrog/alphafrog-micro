package world.willfrog.alphafrogmicro.frontend.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import world.willfrog.alphafrogmicro.frontend.service.FetchPermissionService;

import java.io.IOException;

@Component
@RequiredArgsConstructor
@Slf4j
public class FetchAccessFilter extends OncePerRequestFilter {

    private final FetchPermissionService fetchPermissionService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path == null || !path.startsWith("/fetch/")) {
            chain.doFilter(request, response);
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }

        String username = authentication.getName();
        if (!fetchPermissionService.canAccessFetch(username)) {
            log.warn("Fetch access forbidden for user={} path={}", username, path);
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden");
            return;
        }

        chain.doFilter(request, response);
    }
}
