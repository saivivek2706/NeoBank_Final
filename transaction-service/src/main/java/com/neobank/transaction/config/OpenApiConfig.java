package com.neobank.transaction.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 document for this service (springdoc). Swagger UI is served at /swagger-ui.html and the
 * raw spec at /v3/api-docs; both can be switched off per environment with SWAGGER_UI_ENABLED=false.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI neobankOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("NeoBank Transaction Service API")
                        .version("v1")
                        .description("Transfer initiation (asynchronous saga) and transaction status."))
                .components(new Components().addSecuritySchemes("bearer-jwt",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"));
    }
}
