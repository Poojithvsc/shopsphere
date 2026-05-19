package com.shopsphere.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

final class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final JwtIssuer jwt;

    JwtAuthenticationFilter(JwtIssuer jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER)) {
            String token = header.substring(BEARER.length());
            try {
                JwtIssuer.Verified verified = jwt.verify(token);
                SecurityContextHolder.getContext().setAuthentication(new JwtAuthentication(verified));
            } catch (JwtIssuer.InvalidTokenException ignored) {
            }
        }
        chain.doFilter(request, response);
    }

    private static final class JwtAuthentication extends AbstractAuthenticationToken {

        private final UUID userId;
        private final UUID customerId;

        JwtAuthentication(JwtIssuer.Verified verified) {
            super(List.of());
            this.userId = verified.userId();
            this.customerId = verified.customerId();
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return "";
        }

        @Override
        public Object getPrincipal() {
            return userId;
        }

        @Override
        public String getName() {
            return userId.toString();
        }

        @SuppressWarnings("unused")
        UUID customerId() {
            return customerId;
        }
    }
}
