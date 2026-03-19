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
            String bearerToken = extractBearerToken(request);
            
            if (bearerToken != null && jwtManager != null && jwtManager.validateToken(bearerToken)) {
                String subject = jwtManager.getSubject(bearerToken);
                
                // Create authentication token
                UsernamePasswordAuthenticationToken authToken = 
                    new UsernamePasswordAuthenticationToken(subject, null, new ArrayList<>());
                
                // Set in security context
                SecurityContextHolder.getContext().setAuthentication(authToken);
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
    private String extractBearerToken(HttpServletRequest request) {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7); // Remove "Bearer " prefix
        }
        return null;
    }
}
