package com.hybrid.blockchain.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hybrid.blockchain.api.JwtManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Optional;

/**
 * JWT authentication filter that validates JWT tokens on incoming requests.
 * 
 * - Extracts JWT from Authorization header (Bearer scheme)
 * - Validates token using JwtManager
 * - Sets authenticated principal in SecurityContext
 * - Allows unauthenticated requests through for public endpoints
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    @Autowired(required = false)
    private JwtManager jwtManager;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        try {
            Optional<String> bearerToken = extractBearerToken(request);
            
            if (bearerToken.isPresent() && jwtManager != null && jwtManager.validateToken(bearerToken.get())) {
                Optional<String> subject = jwtManager.getSubjectOptional(bearerToken.get());
                if (subject.isPresent()) {
                    UsernamePasswordAuthenticationToken authToken =
                            new UsernamePasswordAuthenticationToken(subject.get(), null, new ArrayList<>());
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            }
        } catch (Exception e) {
            logger.error("JWT authentication failed: " + e.getMessage());
            // Continue without authentication - let Spring Security handle it
        }
        
        filterChain.doFilter(request, response);
    }

    /**
     * Extract JWT token from Authorization header (Bearer scheme)
     */
    private Optional<String> extractBearerToken(HttpServletRequest request) {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return Optional.of(authHeader.substring(7)); // Remove "Bearer " prefix
        }
        return Optional.empty();
    }
}
