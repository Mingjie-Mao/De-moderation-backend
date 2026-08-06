package com.campusguard.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI is not only developer documentation here: the moderation console is
 * driven through it rather than through a bespoke admin front end, so the
 * descriptions and the auth flow are part of the product surface.
 */
@Configuration
public class OpenApiConfig {

    static final String BEARER_SCHEME = "bearer-jwt";

    @Bean
    public OpenAPI campusGuardOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("CampusGuard API")
                        .version("v0")
                        .description("""
                                Forum and content-moderation backend.

                                Reading the forum is open. Everything else needs a bearer token: \
                                register or log in under Authentication, then paste the \
                                `accessToken` into Authorize."""))
                .components(new Components()
                        .addSecuritySchemes(
                                BEARER_SCHEME,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("Token from POST /api/auth/login")));
    }
}
