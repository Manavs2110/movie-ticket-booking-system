package com.moviebooking.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviebooking.common.ApiError;
import com.moviebooking.common.ErrorCode;
import com.moviebooking.common.ratelimit.RateLimitFilter;
import com.moviebooking.common.ratelimit.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

import java.io.IOException;
import java.util.Map;

@Configuration
public class SecurityConfig {

    private final ObjectMapper objectMapper;
    private final RateLimiter rateLimiter;
    private final AppProperties props;

    public SecurityConfig(ObjectMapper objectMapper, RateLimiter rateLimiter, AppProperties props) {
        this.objectMapper = objectMapper;
        this.rateLimiter = rateLimiter;
        this.props = props;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        AuthenticationEntryPoint jsonEntryPoint = (req, res, ex) ->
                write(req, res, ErrorCode.UNAUTHORIZED, "Missing or incorrect credentials");
        AccessDeniedHandler jsonAccessDenied = (req, res, ex) ->
                write(req, res, ErrorCode.FORBIDDEN, "Access denied");

        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.authenticationEntryPoint(jsonEntryPoint))
                .exceptionHandling(e -> e.authenticationEntryPoint(jsonEntryPoint)
                        .accessDeniedHandler(jsonAccessDenied))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/cities/**", "/api/movies/**", "/api/shows/**").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                // per-user limits need the authenticated user, so they run right after HTTP Basic
                .addFilterAfter(new RateLimitFilter(rateLimiter, objectMapper, props), BasicAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    private void write(HttpServletRequest req, HttpServletResponse res, ErrorCode code, String message)
            throws IOException {
        res.setStatus(code.status().value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(res.getOutputStream(), ApiError.of(code, message, Map.of(), req.getRequestURI()));
    }
}
