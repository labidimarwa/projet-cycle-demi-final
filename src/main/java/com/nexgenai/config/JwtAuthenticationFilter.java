package com.nexgenai.config;

import com.nexgenai.security.SecurityEventLogger;
import com.nexgenai.service.JwtService;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService         jwtService;
    private final UserDetailsService userDetailsService;
    private final SecurityEventLogger securityLogger;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest  request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain         filterChain
    ) throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");
        final String requestURI = request.getRequestURI();
        final String clientIp  = getClientIp(request);

        // For SSE endpoints: EventSource cannot send headers, token comes via ?token= query param
        final String jwt;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            jwt = authHeader.substring(7);
        } else {
            String queryToken = request.getParameter("token");
            if (queryToken == null || queryToken.isBlank()) {
                filterChain.doFilter(request, response);
                return;
            }
            jwt = queryToken;
        }
        String userEmail = null;

        try {
            userEmail = jwtService.extractUsername(jwt);
        } catch (ExpiredJwtException e) {
            securityLogger.expiredJwt(clientIp, requestURI);
            filterChain.doFilter(request, response);
            return;
        } catch (MalformedJwtException e) {
            securityLogger.invalidJwt(clientIp, requestURI, "malformed");
            filterChain.doFilter(request, response);
            return;
        } catch (Exception e) {
            securityLogger.invalidJwt(clientIp, requestURI, e.getMessage());
            filterChain.doFilter(request, response);
            return;
        }

        // Authentifier si email extrait et pas encore authentifié
        if (userEmail != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                UserDetails userDetails = userDetailsService.loadUserByUsername(userEmail);

                if (jwtService.isTokenValid(jwt, userDetails)) {
                    UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(
                            userDetails, null, userDetails.getAuthorities()
                        );
                    authToken.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request)
                    );
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                    log.debug("✅ Authentifié : {} — {}", userEmail, requestURI);
                }
            } catch (Exception e) {
                log.warn("⚠️ Erreur authentification : {}", e.getMessage());
            }
        }

        filterChain.doFilter(request, response);
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}