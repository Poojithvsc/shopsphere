package com.shopsphere;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes a bearer-JWT security scheme on the OpenAPI document so Swagger UI renders the
 * "Authorize" button. Protected controllers reference it via {@code @SecurityRequirement(name =
 * "bearerAuth")}; the public {@code /api/v1/auth/**} endpoints intentionally carry no requirement,
 * so they stay open in the UI. The scheme is documentation only — actual enforcement lives in
 * {@code SecurityConfig} + {@code JwtAuthenticationFilter}.
 */
@Configuration
@OpenAPIDefinition(info = @Info(title = "ShopSphere API", version = "v1"))
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT")
class OpenApiConfig {
}
