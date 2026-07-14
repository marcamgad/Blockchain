package com.hybrid.blockchain.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Security configuration for JWT-based authentication.
 *
 * Rules:
 * - GET endpoints → public
 * - POST/PUT/DELETE → require JWT
 * - Stateless (no sessions)
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity // ✅ Updated (replaces deprecated annotation)
public class SecurityConfig {

    /**
     * Registers the JWT manager as a bean so {@link JwtAuthFilter} actually receives one.
     * Without this the filter's {@code @Autowired(required=false)} field stays null and
     * every token is silently treated as unauthenticated — JWT auth would be a no-op.
     */
    @Bean
    public com.hybrid.blockchain.api.JwtManager jwtManager() {
        return new com.hybrid.blockchain.api.JwtManager();
    }

    // JwtAuthFilter is injected as a method parameter (not a field) to avoid a
    // circular reference: the filter depends on the jwtManager bean declared above,
    // which lives on this same config class.
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter) throws Exception {

        http
            // Disable CSRF (not needed for stateless APIs)
            .csrf(csrf -> csrf.disable())

            // Stateless session (JWT)
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )

            // Authorization rules
            .authorizeHttpRequests(auth -> auth
                // Admin endpoints MUST be matched first
                .requestMatchers("/api/v1/admin/**").authenticated()

                // Public health and metrics endpoints
                .requestMatchers(HttpMethod.GET, "/api/v1/health").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/ready").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/metrics").permitAll()
                .requestMatchers(HttpMethod.GET, "/actuator/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/actuator/prometheus").permitAll()

                // Public GET endpoints (general)
                .requestMatchers(HttpMethod.GET, "/api/**").permitAll()

                .requestMatchers(HttpMethod.POST, "/api/v1/account/create").permitAll()
                // Write endpoints require authentication
                .requestMatchers(HttpMethod.POST, "/api/**").authenticated()
                .requestMatchers(HttpMethod.PUT, "/api/**").authenticated()
                .requestMatchers(HttpMethod.DELETE, "/api/**").authenticated()

                // Everything else requires authentication
                .anyRequest().authenticated()            )

            // Add JWT filter
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)

            // Handle unauthorized access
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(401);
                    response.setContentType("application/json");
                    response.getWriter().write(
                        "{\"error\": \"Unauthorized: " + authException.getMessage() + "\"}"
                    );
                })
            );

        return http.build();
    }

    /**
     * Authentication manager bean
     */
    @Bean
    public AuthenticationManager authenticationManager(HttpSecurity http) throws Exception {
        return http
                .getSharedObject(AuthenticationManagerBuilder.class)
                .build();
    }
}