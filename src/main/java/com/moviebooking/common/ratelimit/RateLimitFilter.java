package com.moviebooking.common.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviebooking.common.ApiError;
import com.moviebooking.common.ErrorCode;
import com.moviebooking.config.AppProperties;
import com.moviebooking.model.auth.AppUserPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Per-user limits (HLD §9.4): hold 10/min, pay 5/min, everything else 100/min. Runs after authentication;
 * anonymous browsing is left to the CDN / gateway. Not a Spring bean on purpose, so Boot doesn't also
 * register it as a plain servlet filter; {@code SecurityConfig} adds it to the security chain.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Pattern PAY = Pattern.compile("^/api/bookings/[^/]+/pay$");

    private final RateLimiter limiter;
    private final ObjectMapper objectMapper;
    private final AppProperties.RateLimitProps limits;

    public RateLimitFilter(RateLimiter limiter, ObjectMapper objectMapper, AppProperties props) {
        this.limiter = limiter;
        this.objectMapper = objectMapper;
        this.limits = props.rateLimit();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppUserPrincipal user)) {
            chain.doFilter(request, response);
            return;
        }
        String endpoint;
        int limit;
        String path = request.getRequestURI();
        if ("POST".equals(request.getMethod()) && "/api/bookings".equals(path)) {
            endpoint = "hold";
            limit = limits.holdPerMinute();
        } else if ("POST".equals(request.getMethod()) && PAY.matcher(path).matches()) {
            endpoint = "pay";
            limit = limits.payPerMinute();
        } else {
            endpoint = "default";
            limit = limits.defaultPerMinute();
        }
        if (limiter.tryAcquire(user.id(), endpoint, limit)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(ErrorCode.RATE_LIMITED.status().value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(limiter.secondsUntilNextWindow()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiError.of(ErrorCode.RATE_LIMITED,
                "Too many requests; limit is " + limit + " per minute", Map.of("limit", limit), path));
    }
}
