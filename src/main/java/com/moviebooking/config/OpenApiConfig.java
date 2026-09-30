package com.moviebooking.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI at {@code /swagger-ui.html}. Click "Authorize" and log in with HTTP Basic
 * (e.g. demo@moviebooking.com / Customer@123 or admin@moviebooking.com / Admin@123).
 */
@Configuration
public class OpenApiConfig {

    private static final String BASIC = "basicAuth";

    @Bean
    public OpenAPI movieBookingOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Movie Ticket Booking System")
                        .version("1.0.0")
                        .description("""
                                Seat-level booking: browse → lock seats (10 min) → pay (Idempotency-Key) → confirm / cancel.

                                **Login:** click *Authorize* and use HTTP Basic.
                                Customer `demo@moviebooking.com` / `Customer@123` · Admin `admin@moviebooking.com` / `Admin@123`.
                                Browsing (`GET /api/cities/**`, `/api/movies/**`, `/api/shows/**`) needs no login.

                                **Mock payment:** body `{"paymentToken":"tok_decline"}` is declined; anything else is approved.
                                """))
                .components(new Components().addSecuritySchemes(BASIC,
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")))
                .addSecurityItem(new SecurityRequirement().addList(BASIC));
    }

    @Bean
    public GroupedOpenApi customerApi() {
        return GroupedOpenApi.builder().group("1-customer")
                .pathsToMatch("/api/**").pathsToExclude("/api/admin/**").build();
    }

    @Bean
    public GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder().group("2-admin").pathsToMatch("/api/admin/**").build();
    }
}
