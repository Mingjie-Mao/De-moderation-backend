package com.campusguard.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI is not only developer documentation here: the moderation console is
 * driven through it rather than through a bespoke admin front end, so the
 * descriptions are part of the product surface.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI campusGuardOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("CampusGuard API")
                        .version("v0")
                        .description("""
                                Forum and content-moderation backend.

                                Authentication is not wired up yet. Endpoints that act on \
                                behalf of a user take an `X-User-Id` header; it is replaced \
                                by an authenticated principal once JWT lands."""));
    }
}
