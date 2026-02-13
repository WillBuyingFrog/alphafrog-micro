package world.willfrog.alphafrogmicro.frontend.filter;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import world.willfrog.alphafrogmicro.frontend.config.JwtConfig;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtConfig jwtConfig;
    private final SecretKey secretKey;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = resolveToken(request);
        String requestUri = request.getRequestURI();
        log.info("JwtAuthFilter processing: uri={}, tokenResolved={}", requestUri, token != null);
        if(token != null && validateToken(token)) {
            Authentication authentication = getAuthentication(token);
            SecurityContextHolder.getContext().setAuthentication(authentication);
            log.info("JWT authentication successful for {}: principal={}, authenticated={}", 
                    requestUri, authentication.getName(), authentication.isAuthenticated());
        } else if (token != null) {
            log.warn("JWT token validation failed for {}", requestUri);
        } else {
            log.info("No JWT token found in request: {}", requestUri);
        }
        chain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader(jwtConfig.getHeader());
        if(StringUtils.hasText(bearerToken) && bearerToken.startsWith(jwtConfig.getTokenPrefix())) {
            return bearerToken.substring(jwtConfig.getTokenPrefix().length()).trim();
        } else {
            return null;
        }
    }

    private boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (Exception e) {
            log.error("JWT token validation failed: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    private Authentication getAuthentication(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        String principal = claims.getSubject();
        // 使用三个参数的构造函数，明确标记为已认证
        List<SimpleGrantedAuthority> authorities = Collections.emptyList();
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }
}
